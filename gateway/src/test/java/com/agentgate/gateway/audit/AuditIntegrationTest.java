package com.agentgate.gateway.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;

import com.agentgate.gateway.AgentCallIntegrationTest;
import com.agentgate.gateway.approval.ApprovalRequest;
import com.agentgate.gateway.approval.ApprovalRequestRepository;
import com.agentgate.gateway.approval.ApprovalState;
import com.agentgate.gateway.registry.RiskLevel;
import com.agentgate.gateway.registry.Tool;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** FR7 / QA3 through the real HTTP pipeline: every call is recorded, the chain verifies, tampering is found. */
class AuditIntegrationTest extends AgentCallIntegrationTest {

    private static final String REFUND = "{\"orderId\": 1001, \"amount\": 100, \"reason\": \"damaged\"}";

    @Autowired JdbcTemplate jdbc;
    @Autowired AuditWriter auditWriter;
    @Autowired ApprovalRequestRepository approvals;

    @BeforeEach
    void emptyTheAuditLog() {
        auditWriter.flush();   // nothing from the previous test may still be queued
        jdbc.update("DELETE FROM audit_records");
        approvals.deleteAll();
    }

    @Test
    void allowedCallIsRecordedWithTheRuleThatAllowedIt() {
        MvcTestResult result = call("support-bot", "1", "getCustomer", "{\"id\": 1}");

        assertThat(result).hasStatusOk();
        Map<String, Object> row = auditRowOf(result);
        assertThat(row).containsEntry("agent_id", "support-bot").containsEntry("end_user_id", "1")
                .containsEntry("tool_name", "getCustomer").containsEntry("decision", "ALLOWED")
                .containsEntry("rule_id", "support-own-customer-only").containsEntry("decided_by_step", "PolicyStep")
                .containsEntry("http_status", 200);
        assertThat(row.get("policy_version")).isNotNull();
        assertThat((Long) row.get("latency_ms")).isNotNegative();
        assertThat(row.get("prev_hash")).isEqualTo("0".repeat(64));   // first record starts at the genesis value
    }

    @Test
    void callsBlockedAtEveryStepAreRecordedAndStillReturnTheCallIdHeader() {
        MvcTestResult policyDenied = call("support-bot", "1", "getCustomer", "{\"id\": 2}");       // other customer
        MvcTestResult schemaInvalid = call("support-bot", "1", "getCustomer", "{\"id\": \"x\"}");
        setKillSwitch("/api/admin/kill-switch/tools/getOrder", true);
        MvcTestResult killed = call("support-bot", null, "getOrder", "{\"id\": \"1001\"}");
        MvcTestResult unknownTool = call("support-bot", null, "noSuchTool", "{}");

        assertThat(policyDenied).hasStatus(403);
        assertThat(auditRowOf(policyDenied)).containsEntry("decision", "DENIED")
                .containsEntry("decided_by_step", "PolicyStep").containsEntry("rule_id", "default-deny")
                .containsEntry("http_status", 403);
        assertThat(schemaInvalid).hasStatus(400);
        assertThat(auditRowOf(schemaInvalid)).containsEntry("decided_by_step", "SchemaValidationStep")
                .containsEntry("http_status", 400).containsEntry("decision", "DENIED");
        assertThat(killed).hasStatus(403);
        assertThat(auditRowOf(killed)).containsEntry("decided_by_step", "KillSwitchStep")
                .containsEntry("rule_id", "kill-switch-tool");
        assertThat(unknownTool).hasStatus(404);
        assertThat(auditRowOf(unknownTool)).containsEntry("http_status", 404).containsEntry("tool_name", "noSuchTool");
    }

    @Test
    void aRateLimitedCallIsRecorded() {
        rateCounters.reset();
        MvcTestResult last = null;
        for (int i = 0; i < 61; i++) {   // agent-per-minute is 60
            last = call("analytics-bot", null, "getOrder", "{\"id\": \"1\"}");
        }
        assertThat(last).hasStatus(429);
        assertThat(auditRowOf(last)).containsEntry("decided_by_step", "LimitStep")
                .containsEntry("rule_id", "agent-rate-per-minute").containsEntry("http_status", 429);
    }

    @Test
    void argumentsAreStoredRedacted() {
        MvcTestResult result = call("support-bot", "1", "issueRefund",
                "{\"orderId\": 1001, \"amount\": 10, \"reason\": \"mail rahim@example.com phone 01712345678 nid 1234567890123\"}");

        assertThat(result).hasStatus(201);
        String stored = (String) auditRowOf(result).get("arguments");
        assertThat(stored).doesNotContain("rahim@example.com", "01712345678", "1234567890123")
                .contains("r***@example.com", "017******78", "12*********23");
    }

    @Test
    void approvedAndRejectedCallsAreRecordedWithTheApprovalDetails() {
        makeRefundHighRisk();

        CompletableFuture<MvcTestResult> approvedCall = refundInBackground();
        UUID approvedId = awaitPending();
        approverPost(approvedId, "approve", "{}");
        MvcTestResult approved = approvedCall.join();

        CompletableFuture<MvcTestResult> rejectedCall = refundInBackground();
        UUID rejectedId = awaitPending();
        approverPost(rejectedId, "reject", "{\"reason\": \"customer not verified\"}");
        MvcTestResult rejected = rejectedCall.join();

        assertThat(approved).hasStatus(201);
        assertThat(auditRowOf(approved)).containsEntry("decision", "ALLOWED")
                .containsEntry("approval_id", approvedId).containsEntry("approval_state", "APPROVED")
                .containsEntry("approver", "approver").containsEntry("rule_id", "support-refund-up-to-2000");
        assertThat(rejected).hasStatus(403);
        assertThat(auditRowOf(rejected)).containsEntry("decision", "DENIED")
                .containsEntry("decided_by_step", "ApprovalStep").containsEntry("rule_id", "approval-rejected")
                .containsEntry("approval_id", rejectedId).containsEntry("approval_state", "REJECTED");
    }

    @Test
    void editedApprovalStoresTheEditedArgumentsToo() {
        makeRefundHighRisk();
        CompletableFuture<MvcTestResult> agent = refundInBackground();
        UUID id = awaitPending();
        approverPost(id, "edit", "{\"arguments\": {\"orderId\": 1001, \"amount\": 40, \"reason\": \"damaged\"}}");

        Map<String, Object> row = auditRowOf(agent.join());
        assertThat(row).containsEntry("approval_state", "APPROVED_WITH_EDITS");
        assertThat((String) row.get("edited_arguments")).contains("\"amount\":40");
        assertThat((String) row.get("arguments")).contains("\"amount\":100");
    }

    @Test
    void fiftyParallelCallsGiveOneUnbrokenChain() {
        String token = tokenFor("support-bot");
        List<CompletableFuture<MvcTestResult>> calls = IntStream.range(0, 50)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> mvc.post().uri("/api/agent/calls/getCustomer")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header("X-On-Behalf-Of", "1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"id\": 1}").exchange()))
                .toList();
        calls.forEach(c -> assertThat(c.join()).hasStatusOk());

        assertThat(verify()).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.status", v -> v.assertThat().isEqualTo("OK"))
                .hasPathSatisfying("$.records", v -> v.assertThat().isEqualTo(50));
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT seq) FROM audit_records", Long.class)).isEqualTo(50);
    }

    @Test
    void changingOneStoredRowIsDetectedAndTheBrokenSequenceNumberIsReported() {
        for (int i = 0; i < 5; i++) {
            assertThat(call("support-bot", "1", "getCustomer", "{\"id\": 1}")).hasStatusOk();
        }
        assertThat(verify()).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.status", v -> v.assertThat().isEqualTo("OK"));

        jdbc.update("UPDATE audit_records SET rule_id = 'edited-by-attacker' WHERE seq = 3");

        assertThat(verify()).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.status", v -> v.assertThat().isEqualTo("BROKEN"))
                .hasPathSatisfying("$.firstBrokenSeq", v -> v.assertThat().isEqualTo(3));
    }

    @Test
    void aDeletedRecordInTheMiddleIsDetected() {
        for (int i = 0; i < 4; i++) {
            assertThat(call("support-bot", "1", "getCustomer", "{\"id\": 1}")).hasStatusOk();
        }
        auditWriter.flush();
        jdbc.update("DELETE FROM audit_records WHERE seq = 2");

        assertThat(verify()).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.status", v -> v.assertThat().isEqualTo("BROKEN"))
                .hasPathSatisfying("$.firstBrokenSeq", v -> v.assertThat().isEqualTo(3));
    }

    @Test
    void thereIsNoEndpointToChangeOrDeleteRecordsAndVerifyNeedsAdmin() {
        // /api/admin/audit/{callId} exists for GET only, so any other method is refused
        assertThat(mvc.delete().uri("/api/admin/audit/1").with(admin())).hasStatus(405);
        assertThat(mvc.put().uri("/api/admin/audit/1").with(admin())).hasStatus(405);
        assertThat(mvc.delete().uri("/api/admin/audit").with(admin())).hasStatus(405);
        assertThat(mvc.put().uri("/api/admin/audit/verify").with(admin())).hasStatus(405);
        assertThat(mvc.get().uri("/api/admin/audit/verify")).hasStatus(401);
    }

    // ---------- helpers ----------

    /** Finds the audit row of a call by the X-Call-Id header the agent received. */
    private Map<String, Object> auditRowOf(MvcTestResult result) {
        String callId = result.getResponse().getHeader("X-Call-Id");
        assertThat(callId).as("X-Call-Id response header").isNotBlank();
        auditWriter.flush();
        return jdbc.queryForMap("SELECT * FROM audit_records WHERE call_id = ?", UUID.fromString(callId));
    }

    private MvcTestResult verify() {
        return mvc.get().uri("/api/admin/audit/verify").with(admin()).exchange();
    }

    private void makeRefundHighRisk() {
        Tool refund = toolRepository.findByName("issueRefund").orElseThrow();
        refund.setRiskLevel(RiskLevel.HIGH);
        toolRepository.save(refund);
    }

    private CompletableFuture<MvcTestResult> refundInBackground() {
        return CompletableFuture.supplyAsync(() -> call("support-bot", "1", "issueRefund", REFUND));
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

    private void approverPost(UUID id, String action, String body) {
        mvc.post().uri("/api/approvals/{id}/{action}", id, action).with(httpBasic("approver", "approver-local-only"))
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }
}
