package com.agentgate.agentsim;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A scripted "agent" (the course says: scripted MCP clients act as agents). It talks to the gateway's /mcp endpoint
 * with Spring AI's MCP client and an OAuth2 client-credentials token, and prints what happens:
 *
 *   1. tools/list                          what this agent may see
 *   2. getOrder 1001                       allowed by policy
 *   3. getCustomer 2 (acting for 1)        denied by policy (other customer)
 *   4. track_parcel BD1001                 a tool imported from a downstream MCP server
 *   5. issueRefund 500 BDT                 HIGH risk: held until a human approves (the sim plays the approver)
 *
 * Settings come from environment variables; the defaults match the "local" profile of the gateway.
 */
public class AgentSim {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private final String gateway = env("GATEWAY_URL", "http://localhost:8080");
    private final String agentId = env("AGENT_ID", "support-bot");
    private final String agentSecret = env("AGENT_SECRET", "support-bot-local-secret");
    private final String approverUser = env("APPROVER_USERNAME", "approver");
    private final String approverPassword = env("APPROVER_PASSWORD", "approver-local-only");

    /** Sent as X-On-Behalf-Of on every MCP request: the end user this agent acts for (FR2). */
    private volatile String endUser;

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "qa1".equals(args[0])) {
            // QA1 red-team suite (see qa/redteam). Exit code 1 = QA1 failed.
            System.exit(com.agentgate.agentsim.qa1.Qa1Runner.runFromAgentSim());
        }
        new AgentSim().run();
    }

    private void run() throws Exception {
        String token = fetchToken();
        System.out.println("== agent '" + agentId + "' got an OAuth2 access token (client credentials)");

        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(gateway)
                .endpoint("/mcp")
                .httpRequestCustomizer((request, method, uri, body, context) -> {
                    request.header("Authorization", "Bearer " + token);
                    if (endUser != null) {
                        request.header("X-On-Behalf-Of", endUser);
                    }
                })
                .build();
        // The refund waits for a human, so the client must be willing to wait longer than the default.
        try (McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(60))
                .clientInfo(new McpSchema.Implementation("agent-sim", "0.1.0"))
                .build()) {
            McpSchema.InitializeResult init = client.initialize();
            System.out.println("== connected to MCP server '" + init.serverInfo().name() + "' (protocol "
                    + init.protocolVersion() + ")");

            step("1. tools/list: which tools may this agent use?");
            for (McpSchema.Tool tool : client.listTools().tools()) {
                System.out.println("   - " + tool.name() + ": " + shorten(tool.description(), 70));
            }

            endUser = "1";   // from here on the agent acts for customer 1
            step("2. getOrder 1001 (policy: allowed)");
            show(client.callTool(call("getOrder", Map.of("id", 1001))));

            step("3. getCustomer 2 while acting for customer 1 (policy: denied)");
            show(client.callTool(call("getCustomer", Map.of("id", 2))));

            step("4. track_parcel BD1001 (a tool imported from a downstream MCP server)");
            show(client.callTool(call("track_parcel", Map.of("trackingNumber", "BD1001"))));

            step("5. issueRefund 500 BDT (HIGH risk: held for human approval)");
            refundWithApproval(client);
        }
    }

    private void refundWithApproval(McpSyncClient client) throws Exception {
        CompletableFuture<McpSchema.CallToolResult> pending = CompletableFuture.supplyAsync(() ->
                client.callTool(call("issueRefund", Map.of("orderId", 1002, "amount", 500, "reason", "damaged item"))));

        // The agent's call is now blocked at the gateway. Play the human approver: find it and approve it.
        String approvalId = waitForPendingApproval();
        System.out.println("   the call is HELD. Pending approval " + approvalId + " (the agent is still waiting)");
        String approved = post("/api/approvals/" + approvalId + "/approve", "{}",
                basic(approverUser, approverPassword));
        System.out.println("   approver '" + approverUser + "' approves -> state "
                + JSON.readTree(approved).path("state").asString());

        show(pending.get());
    }

    private String waitForPendingApproval() throws Exception {
        for (int i = 0; i < 40; i++) {
            Thread.sleep(250);
            JsonNode page = JSON.readTree(get("/api/approvals?state=PENDING", basic(approverUser, approverPassword)));
            for (JsonNode approval : page.path("content")) {
                if ("issueRefund".equals(approval.path("toolName").asString())
                        && agentId.equals(approval.path("agentId").asString())) {
                    return approval.path("id").asString();
                }
            }
        }
        throw new IllegalStateException("No pending approval appeared; was the refund not held?");
    }

    // ----- small helpers -----

    private static McpSchema.CallToolRequest call(String tool, Map<String, Object> arguments) {
        return new McpSchema.CallToolRequest(tool, arguments);
    }

    private static void step(String title) {
        System.out.println();
        System.out.println("-- " + title);
    }

    private static void show(McpSchema.CallToolResult result) {
        String text = result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(c -> ((McpSchema.TextContent) c).text())
                .reduce("", (a, b) -> a + b);
        System.out.println("   result: " + (Boolean.TRUE.equals(result.isError()) ? "ERROR/BLOCKED" : "OK"));
        System.out.println("   " + text);
        Object callId = result.meta() == null ? null : result.meta().get("agentgate/callId");
        if (callId != null) {
            System.out.println("   callId: " + callId);
        }
    }

    private String fetchToken() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(gateway + "/oauth2/token"))
                .header("Authorization", basic(agentId, agentSecret))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials"))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Token request failed: HTTP " + response.statusCode() + " " + response.body());
        }
        return JSON.readTree(response.body()).path("access_token").asString();
    }

    private String get(String path, String authorization) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(gateway + path))
                .header("Authorization", authorization).GET().build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    private String post(String path, String body, String authorization) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(gateway + path))
                .header("Authorization", authorization)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private static String shorten(String text, int max) {
        return text == null ? "" : text.length() <= max ? text : text.substring(0, max) + "...";
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
