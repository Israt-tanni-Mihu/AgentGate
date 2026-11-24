package com.agentgate.gateway.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentgate.gateway.AgentCallIntegrationTest;
import com.agentgate.gateway.audit.AuditWriter;
import com.agentgate.gateway.policy.CreatePolicyVersionRequest;
import com.agentgate.gateway.policy.PolicyAdminService;
import com.agentgate.gateway.policy.PolicyRule;
import com.agentgate.gateway.registry.RiskLevel;
import com.agentgate.gateway.registry.ToolKind;
import com.agentgate.gateway.registry.mcp.McpServerRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;

/**
 * FR1: a DOWNSTREAM MCP server. The gateway imports its tools with the MCP client (tools/list) and forwards calls
 * with tools/call. The downstream server is a tiny fake (JDK HttpServer speaking JSON-RPC), so tests can make it
 * fail on purpose and count the calls that really reach it.
 */
class McpDownstreamIntegrationTest extends AgentCallIntegrationTest {

    private static HttpServer mcpServer;
    private static final List<String> mcpToolCalls = new CopyOnWriteArrayList<>();
    private static volatile boolean mcpBroken;       // tools/call answers HTTP 500
    private static volatile boolean mcpToolError;    // tools/call answers a normal result with isError = true
    private static volatile long mcpDelayMs;         // tools/call is slow

    @Autowired McpServerRepository mcpServers;
    @Autowired PolicyAdminService policyService;
    @Autowired AuditWriter auditWriter;
    @Autowired JdbcTemplate jdbc;

    @BeforeAll
    static void startFakeMcpServer() throws IOException {
        mcpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        mcpServer.createContext("/mcp", McpDownstreamIntegrationTest::answer);
        mcpServer.setExecutor(Executors.newCachedThreadPool());
        mcpServer.start();
    }

    @AfterAll
    static void stopFakeMcpServer() {
        mcpServer.stop(0);
    }

    @BeforeEach
    void cleanUp() {
        mcpToolCalls.clear();
        mcpBroken = false;
        mcpToolError = false;
        mcpDelayMs = 0;
        mcpServers.deleteAll();
        auditWriter.flush();
        jdbc.update("DELETE FROM audit_records");
    }

    @AfterEach
    void restoreDemoPolicy() {
        policyService.activate(1);
    }

    // ---------- import (Adapter) ----------

    @Test
    void importingFromAnMcpServerCreatesMcpToolsWithTheServersDefaultRiskLevel() {
        int serverId = registerServer(RiskLevel.MEDIUM);

        MvcTestResult imported = importServer(serverId);

        assertThat(imported).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.format", v -> v.assertThat().isEqualTo("mcp-tools-list"))
                .hasPathSatisfying("$.created", v -> v.assertThat().isEqualTo(1));
        var tool = toolRepository.findByName("track_parcel").orElseThrow();
        assertThat(tool.getKind()).isEqualTo(ToolKind.MCP);
        assertThat(tool.getRiskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(tool.getOwnerTeam()).isEqualTo("logistics-team");
        assertThat(tool.getBaseUrl()).isEqualTo(serverUrl());
        assertThat(tool.getHttpMethod()).isNull();
        assertThat(tool.getAccessMode().name()).isEqualTo("READ");   // the server said readOnlyHint = true
        assertThat(tool.getInputSchema()).contains("trackingNumber");
    }

    @Test
    void anAdminCanChangeTheRiskLevelAndAReImportKeepsItWithoutDuplicates() {
        int serverId = registerServer(RiskLevel.LOW);
        importServer(serverId);
        Long toolId = toolRepository.findByName("track_parcel").orElseThrow().getId();

        MvcTestResult changed = mvc.put().uri("/api/admin/tools/{id}", toolId).with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"track_parcel","description":"x","inputSchema":{"type":"object"},"riskLevel":"HIGH",
                         "ownerTeam":"logistics-team","kind":"MCP","baseUrl":"%s"}""".formatted(serverUrl()))
                .exchange();
        assertThat(changed).hasStatusOk();

        assertThat(importServer(serverId)).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.created", v -> v.assertThat().isEqualTo(0))
                .hasPathSatisfying("$.updated", v -> v.assertThat().isEqualTo(1));   // description/schema refreshed
        assertThat(toolRepository.findByName("track_parcel").orElseThrow().getRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(toolRepository.count()).isEqualTo(4);   // 3 REST tools from the base class + 1 MCP tool
    }

    @Test
    void anImportNeverTakesOverARestToolWithTheSameName() {
        // the base class already registered the REST tool "getOrder"; a server offering "getOrder" must not replace it
        int serverId = registerServer(RiskLevel.LOW);
        MvcTestResult imported = importServer(serverId);

        assertThat(imported).hasStatusOk();
        assertThat(toolRepository.findByName("getOrder").orElseThrow().getKind()).isEqualTo(ToolKind.REST);
    }

    @Test
    void importingFromAnUnreachableServerIsAClearBadGateway() {
        MvcTestResult created = mvc.post().uri("/api/admin/mcp-servers").with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"dead","url":"http://localhost:1/mcp","ownerTeam":"logistics-team","defaultRiskLevel":"LOW"}""")
                .exchange();
        int id = json(created).get("id").asInt();

        assertThat(importServer(id)).hasStatus(502).bodyJson()
                .hasPathSatisfying("$.title", v -> v.assertThat().isEqualTo("Tool spec import failed"));
    }

    // ---------- forwarding (Strategy) through the whole pipeline ----------

    @Test
    void aCallToAnMcpToolGoesThroughThePipelineIsForwardedAndAudited() {
        importAndAllowTrackParcel();

        MvcTestResult result = call("support-bot", "1", "track_parcel", "{\"trackingNumber\": \"BD1001\"}");

        assertThat(result).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.status", v -> v.assertThat().isEqualTo("Delivered to Dhaka"))
                .hasPathSatisfying("$.trackingNumber", v -> v.assertThat().isEqualTo("BD1001"));
        assertThat(mcpToolCalls).containsExactly("BD1001");

        auditWriter.flush();
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM audit_records WHERE tool_name = 'track_parcel' ORDER BY seq DESC LIMIT 1");
        assertThat(row).containsEntry("decision", "ALLOWED").containsEntry("agent_id", "support-bot")
                .containsEntry("end_user_id", "1").containsEntry("rule_id", "support-track-parcel");
    }

    @Test
    void theSchemaGuardAlsoProtectsMcpTools() {
        importAndAllowTrackParcel();

        assertThat(call("support-bot", "1", "track_parcel", "{\"trackingNumber\": 5}")).hasStatus(400);
        assertThat(call("support-bot", "1", "track_parcel", "{\"trackingNumber\": \"x\", \"extra\": 1}")).hasStatus(400);
        assertThat(mcpToolCalls).isEmpty();   // nothing reached the downstream server
    }

    @Test
    void anAgentWithoutARuleIsDeniedAnMcpToolToo() {
        importAndAllowTrackParcel();

        assertThat(call("analytics-bot", null, "track_parcel", "{\"trackingNumber\": \"BD1001\"}")).hasStatus(403);
        assertThat(mcpToolCalls).isEmpty();
    }

    @Test
    void theToolsOwnErrorResultIsA422AndDoesNotOpenTheBreaker() {
        importAndAllowTrackParcel();
        mcpToolError = true;

        for (int i = 0; i < 8; i++) {
            assertThat(call("support-bot", "1", "track_parcel", "{\"trackingNumber\": \"BD1001\"}")).hasStatus(422)
                    .bodyJson().hasPathSatisfying("$.error", v -> v.assertThat().isEqualTo("unknown parcel"));
        }
        assertThat(toolBreakers.isOpen("track_parcel")).isFalse();
    }

    // ---------- FR10: breaker and timeout apply to MCP tools too ----------

    @Test
    void theBreakerOpensForAnMcpToolThenFailsFastAndHidesTheTool() {
        importAndAllowTrackParcel();
        assertThat(toolNamesFor("support-bot")).contains("track_parcel");
        mcpBroken = true;

        for (int i = 0; i < 5; i++) {
            assertThat(call("support-bot", "1", "track_parcel", "{\"trackingNumber\": \"BD1001\"}")).hasStatus(502);
        }
        int callsBeforeOpen = mcpToolCalls.size();
        assertThat(callsBeforeOpen).isEqualTo(5);

        assertThat(call("support-bot", "1", "track_parcel", "{\"trackingNumber\": \"BD1001\"}")).hasStatus(503)
                .bodyJson().hasPathSatisfying("$.rule", v -> v.assertThat().isEqualTo("circuit-open"));
        assertThat(mcpToolCalls).hasSize(callsBeforeOpen);   // fail fast: the downstream server is not called
        assertThat(toolNamesFor("support-bot")).doesNotContain("track_parcel");
    }

    @Test
    void aSlowMcpToolAnswers504WithinItsOwnTimeLimit() {
        importAndAllowTrackParcel();
        var tool = toolRepository.findByName("track_parcel").orElseThrow();
        tool.setTimeoutMs(300);
        toolRepository.save(tool);
        mcpDelayMs = 2000;
        try {
            assertThat(call("support-bot", "1", "track_parcel", "{\"trackingNumber\": \"BD1001\"}")).hasStatus(504)
                    .bodyJson().hasPathSatisfying("$.rule", v -> v.assertThat().isEqualTo("tool-timeout"));
        } finally {
            mcpDelayMs = 0;
        }
    }

    // ---------- helpers ----------

    private static String serverUrl() {
        return "http://localhost:" + mcpServer.getAddress().getPort() + "/mcp";
    }

    private int registerServer(RiskLevel defaultRisk) {
        MvcTestResult created = mvc.post().uri("/api/admin/mcp-servers").with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"shop-parcels","url":"%s","ownerTeam":"logistics-team","defaultRiskLevel":"%s"}"""
                        .formatted(serverUrl(), defaultRisk))
                .exchange();
        assertThat(created).hasStatus(201);
        return json(created).get("id").asInt();
    }

    private MvcTestResult importServer(int serverId) {
        return mvc.post().uri("/api/admin/mcp-servers/{id}/import", serverId).with(admin()).exchange();
    }

    /** Registers + imports the server and activates a policy version with a rule for track_parcel (default deny otherwise). */
    private void importAndAllowTrackParcel() {
        importServer(registerServer(RiskLevel.LOW));
        var draft = policyService.createDraft(new CreatePolicyVersionRequest("allow track_parcel", false, List.of(
                new PolicyRule("support-track-parcel", "support-bot may track parcels", PolicyRule.Effect.ALLOW,
                        List.of("support-bot"), List.of("track_parcel"), List.of()))));
        policyService.activate(draft.version());
    }

    private List<String> toolNamesFor(String agent) {
        MvcTestResult result = mvc.get().uri("/api/agent/tools")
                .header("Authorization", "Bearer " + tokenFor(agent)).exchange();
        List<String> names = new java.util.ArrayList<>();
        JsonNode body = json(result);
        for (JsonNode tool : body.isArray() ? body : body.path("content")) {
            names.add(tool.get("name").asString());
        }
        return names;
    }

    /** The fake downstream MCP server: initialize, notifications, tools/list and tools/call as JSON-RPC over HTTP POST. */
    private static void answer(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) {
            exchange.sendResponseHeaders(405, -1);   // no server-initiated stream, no DELETE: allowed by the MCP spec
            exchange.close();
            return;
        }
        JsonNode message = new tools.jackson.databind.ObjectMapper()
                .readTree(exchange.getRequestBody().readAllBytes());
        String method = message.path("method").asString();
        if (message.get("id") == null) {
            exchange.sendResponseHeaders(202, -1);   // notification
            exchange.close();
            return;
        }
        String id = message.get("id").toString();
        String result = switch (method) {
            case "initialize" -> """
                    {"protocolVersion":"2025-06-18","capabilities":{"tools":{"listChanged":false}},
                     "serverInfo":{"name":"fake-parcels","version":"1"}}""";
            case "tools/list" -> """
                    {"tools":[{"name":"track_parcel","description":"Read-only. Tracks a parcel.",
                      "inputSchema":{"type":"object","properties":{"trackingNumber":{"type":"string"}},
                                     "required":["trackingNumber"],"additionalProperties":false},
                      "annotations":{"readOnlyHint":true}},
                     {"name":"getOrder","description":"clashes with a REST tool","inputSchema":{"type":"object"}},
                     {"name":"bad name!","description":"unusable name","inputSchema":{"type":"object"}}]}""";
            case "tools/call" -> toolCall(message);
            default -> "{}";
        };
        if (result == null) {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
            return;
        }
        byte[] bytes = ("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /** null = answer HTTP 500 (a broken tool). */
    private static String toolCall(JsonNode message) {
        mcpToolCalls.add(message.at("/params/arguments/trackingNumber").asString());
        if (mcpDelayMs > 0) {
            try {
                Thread.sleep(mcpDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (mcpBroken) {
            return null;
        }
        if (mcpToolError) {
            return "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"error\\\":\\\"unknown parcel\\\"}\"}],\"isError\":true}";
        }
        return "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"trackingNumber\\\":\\\"BD1001\\\",\\\"status\\\":\\\"Delivered to Dhaka\\\"}\"}],\"isError\":false}";
    }
}
