package com.agentgate.gateway.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;

import com.agentgate.gateway.AgentCallIntegrationTest;
import com.agentgate.gateway.approval.ApprovalRequest;
import com.agentgate.gateway.approval.ApprovalRequestRepository;
import com.agentgate.gateway.approval.ApprovalState;
import com.agentgate.gateway.audit.AuditWriter;
import com.agentgate.gateway.registry.RiskLevel;
import com.agentgate.gateway.registry.Tool;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;

/** The MCP endpoint (/mcp) must give the same answers and run the same pipeline as the REST agent API. */
class McpEndpointIntegrationTest extends AgentCallIntegrationTest {

    private static final String REFUND = "{\"orderId\": 1001, \"amount\": 100, \"reason\": \"damaged\"}";

    @Autowired JdbcTemplate jdbc;
    @Autowired AuditWriter auditWriter;
    @Autowired ApprovalRequestRepository approvals;

    @BeforeEach
    void cleanUp() {
        auditWriter.flush();
        jdbc.update("DELETE FROM audit_records");
        approvals.deleteAll();
    }

    @Test
    void initializeAnswersWithServerInfoAndToolsCapability() {
        JsonNode answer = rpc("support-bot", null, "initialize",
                "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}");

        assertThat(answer.at("/result/protocolVersion").asString()).isEqualTo("2025-06-18");
        assertThat(answer.at("/result/serverInfo/name").asString()).isEqualTo("agentgate");
        assertThat(answer.at("/result/capabilities/tools").isObject()).isTrue();
    }

    @Test
    void aNotificationGets202AndNoBody() throws Exception {
        MvcTestResult result = post("support-bot", null, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");

        assertThat(result).hasStatus(202);
        assertThat(result.getResponse().getContentAsString()).isEmpty();
    }

    @Test
    void toolsListDiffersBetweenTwoAgentsWithDifferentPolicies() {
        assertThat(listedTools("support-bot")).containsExactlyInAnyOrder("getCustomer", "getOrder", "issueRefund");
        assertThat(listedTools("analytics-bot")).containsExactlyInAnyOrder("getCustomer", "getOrder");   // no refund
    }

    @Test
    void theListCarriesDescriptionAndInputSchemaFromTheRegistry() {
        JsonNode tools = rpc("support-bot", null, "tools/list", null).at("/result/tools");
        JsonNode refund = null;
        for (JsonNode tool : tools) {
            if (tool.get("name").asString().equals("issueRefund")) {
                refund = tool;
            }
        }
        assertThat(refund).isNotNull();
        assertThat(refund.get("description").asString()).isEqualTo("issueRefund");
        assertThat(refund.at("/inputSchema/required").toString()).contains("orderId", "amount", "reason");
        assertThat(refund.at("/annotations/readOnlyHint").asBoolean()).isFalse();
    }

    @Test
    void aKilledToolIsNotListed() {
        setKillSwitch("/api/admin/kill-switch/tools/getOrder", true);

        assertThat(listedTools("support-bot")).containsExactlyInAnyOrder("getCustomer", "issueRefund");
    }

    @Test
    void aToolWithAnOpenBreakerIsNotListed() {
        shopFailStatus = 503;
        for (int i = 0; i < 5; i++) {
            call("support-bot", "1", "getOrder", "{\"id\": \"1001\"}");
        }
        assertThat(toolBreakers.isOpen("getOrder")).isTrue();

        assertThat(listedTools("support-bot")).doesNotContain("getOrder").contains("getCustomer");
    }

    @Test
    void toolsCallAllowedReturnsTheToolResultAndIsAudited() {
        JsonNode answer = rpc("support-bot", "1", "tools/call",
                "{\"name\":\"getCustomer\",\"arguments\":{\"id\":1}}");

        JsonNode result = answer.get("result");
        assertThat(result.get("isError").asBoolean()).isFalse();
        assertThat(result.at("/content/0/text").asString()).contains("\"result\":\"ok\"");
        assertThat(auditRow(result)).containsEntry("decision", "ALLOWED").containsEntry("agent_id", "support-bot")
                .containsEntry("end_user_id", "1").containsEntry("tool_name", "getCustomer");
    }

    @Test
    void toolsCallDeniedByPolicyIsAnErrorResultWithReasonAndCallIdAndIsAudited() {
        // support-bot acts for customer 1 but asks for customer 2: the policy must say no.
        JsonNode answer = rpc("support-bot", "1", "tools/call", "{\"name\":\"getCustomer\",\"arguments\":{\"id\":2}}");

        JsonNode result = answer.get("result");
        assertThat(answer.has("error")).isFalse();
        assertThat(result.get("isError").asBoolean()).isTrue();
        String callId = result.at("/_meta/agentgate~1callId").asString();
        assertThat(result.at("/content/0/text").asString()).contains(callId).contains("default-deny");
        assertThat(received).isEmpty();   // never reached the shop
        assertThat(auditRow(result)).containsEntry("decision", "DENIED").containsEntry("rule_id", "default-deny");
    }

    @Test
    void guardsAndKillSwitchAlsoApplyOverMcp() {
        JsonNode badSchema = rpc("support-bot", "1", "tools/call", "{\"name\":\"getCustomer\",\"arguments\":{\"id\":\"x\"}}");
        assertThat(badSchema.at("/result/isError").asBoolean()).isTrue();
        assertThat(auditRow(badSchema.get("result"))).containsEntry("decided_by_step", "SchemaValidationStep");

        setKillSwitch("/api/admin/kill-switch/tools/getOrder", true);
        JsonNode killed = rpc("support-bot", null, "tools/call", "{\"name\":\"getOrder\",\"arguments\":{\"id\":\"1001\"}}");
        assertThat(killed.at("/result/isError").asBoolean()).isTrue();
        assertThat(auditRow(killed.get("result"))).containsEntry("rule_id", "kill-switch-tool");

        JsonNode unknown = rpc("support-bot", null, "tools/call", "{\"name\":\"noSuchTool\",\"arguments\":{}}");
        assertThat(unknown.at("/result/isError").asBoolean()).isTrue();
        assertThat(auditRow(unknown.get("result"))).containsEntry("tool_name", "noSuchTool");
    }

    @Test
    void aHighRiskCallWaitsForApprovalOverMcpAndThenCompletes() {
        Tool refund = toolRepository.findByName("issueRefund").orElseThrow();
        refund.setRiskLevel(RiskLevel.HIGH);
        toolRepository.save(refund);

        CompletableFuture<JsonNode> agent = CompletableFuture.supplyAsync(() ->
                rpc("support-bot", "1", "tools/call", "{\"name\":\"issueRefund\",\"arguments\":" + REFUND + "}"));
        UUID id = awaitPending();
        assertThat(received).isEmpty();   // held

        assertThat(mvc.post().uri("/api/approvals/{id}/approve", id).with(httpBasic("approver", "approver-local-only"))
                .contentType(MediaType.APPLICATION_JSON).content("{}").exchange()).hasStatusOk();

        JsonNode result = agent.join().get("result");
        assertThat(result.get("isError").asBoolean()).isFalse();
        assertThat(received).hasSize(1);
        assertThat(auditRow(result)).containsEntry("approval_state", "APPROVED");
    }

    @Test
    void anExpiredApprovalIsAnErrorResultNotACrash() {
        Tool refund = toolRepository.findByName("issueRefund").orElseThrow();
        refund.setRiskLevel(RiskLevel.HIGH);
        toolRepository.save(refund);

        // nobody answers; the test timeout is 3 s
        JsonNode result = rpc("support-bot", "1", "tools/call",
                "{\"name\":\"issueRefund\",\"arguments\":" + REFUND + "}").get("result");

        assertThat(result.get("isError").asBoolean()).isTrue();
        assertThat(result.at("/content/0/text").asString()).contains("approval-expired");
        assertThat(received).isEmpty();
    }

    @Test
    void callsWithoutAValidTokenAre401() {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";

        assertThat(mvc.post().uri("/mcp").contentType(MediaType.APPLICATION_JSON).content(body)).hasStatus(401);
        assertThat(mvc.post().uri("/mcp").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token")
                .contentType(MediaType.APPLICATION_JSON).content(body)).hasStatus(401);
    }

    @Test
    void unknownMethodsAndBadParamsGiveJsonRpcErrors() {
        assertThat(rpc("support-bot", null, "resources/list", null).at("/error/code").asInt()).isEqualTo(-32601);
        assertThat(rpc("support-bot", null, "tools/call", "{}").at("/error/code").asInt()).isEqualTo(-32602);
    }

    // ---------- helpers ----------

    private List<String> listedTools(String agent) {
        List<String> names = new ArrayList<>();
        rpc(agent, null, "tools/list", null).at("/result/tools").forEach(t -> names.add(t.get("name").asString()));
        return names;
    }

    private JsonNode rpc(String agent, String endUser, String method, String params) {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\""
                + (params == null ? "" : ",\"params\":" + params) + "}";
        MvcTestResult result = post(agent, endUser, body);
        assertThat(result).hasStatusOk();
        return json(result);
    }

    private MvcTestResult post(String agent, String endUser, String body) {
        var request = mvc.post().uri("/mcp").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(agent))
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).content(body);
        if (endUser != null) {
            request = request.header("X-On-Behalf-Of", endUser);
        }
        return request.exchange();
    }

    /** The audit record of an MCP call, found by the callId in the tool result. */
    private java.util.Map<String, Object> auditRow(JsonNode toolResult) {
        String callId = toolResult.at("/_meta/agentgate~1callId").asString();
        assertThat(callId).isNotBlank();
        auditWriter.flush();
        return jdbc.queryForMap("SELECT * FROM audit_records WHERE call_id = ?", UUID.fromString(callId));
    }

    private UUID awaitPending() {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            List<ApprovalRequest> pending = approvals.findByState(ApprovalState.PENDING);
            if (!pending.isEmpty()) {
                return pending.getFirst().getId();
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("no PENDING approval request appeared");
    }
}
