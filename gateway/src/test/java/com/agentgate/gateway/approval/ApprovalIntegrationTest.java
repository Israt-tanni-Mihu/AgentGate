package com.agentgate.gateway.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;

import com.agentgate.gateway.AgentCallIntegrationTest;
import com.agentgate.gateway.registry.RiskLevel;
import com.agentgate.gateway.registry.Tool;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * FR4 through the real HTTP pipeline. The agent call is held on another thread while the test plays the approver.
 * The approval timeout is 3 s here (see AgentCallIntegrationTest); support-bot's refund policy is "amount ≤ 2000".
 */
class ApprovalIntegrationTest extends AgentCallIntegrationTest {

    private static final String REFUND = "{\"orderId\": 1001, \"amount\": 100, \"reason\": \"damaged\"}";

    @Autowired ApprovalRequestRepository approvals;

    @BeforeEach
    void makeRefundHighRiskAndClearApprovals() {
        approvals.deleteAll();
        Tool refund = toolRepository.findByName("issueRefund").orElseThrow();
        refund.setRiskLevel(RiskLevel.HIGH);
        toolRepository.save(refund);
    }

    @Test
    void approvedCallIsForwardedAndAgentGetsTheNormalResult() {
        CompletableFuture<MvcTestResult> agent = agentCalls(REFUND);
        UUID id = awaitPending();
        assertThat(received).isEmpty();   // held: nothing reached the shop yet

        assertThat(approverPost(id, "approve", "{}")).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.state", v -> v.assertThat().isEqualTo("APPROVED"))
                .hasPathSatisfying("$.approver", v -> v.assertThat().isEqualTo("approver"));

        assertThat(agent.join()).hasStatus(201);
        assertThat(received).hasSize(1);
        // A second decision on the same request is an illegal transition → 409 Problem Details.
        assertThat(approverPost(id, "approve", "{}")).hasStatus(409);
    }

    @Test
    void rejectedCallGivesTheAgentA403WithApprovalIdStateAndReason() {
        CompletableFuture<MvcTestResult> agent = agentCalls(REFUND);
        UUID id = awaitPending();

        assertThat(approverPost(id, "reject", "{\"reason\": \"customer not verified\"}")).hasStatusOk();

        assertThat(agent.join()).hasStatus(403).bodyJson()
                .hasPathSatisfying("$.approvalId", v -> v.assertThat().isEqualTo(id.toString()))
                .hasPathSatisfying("$.state", v -> v.assertThat().isEqualTo("REJECTED"))
                .hasPathSatisfying("$.reason", v -> v.assertThat().isEqualTo("customer not verified"))
                .hasPathSatisfying("$.rule", v -> v.assertThat().isEqualTo("approval-rejected"));
        assertThat(received).isEmpty();
    }

    @Test
    void unansweredRequestExpiresAndTheAgentGetsAClearResult() {
        MvcTestResult result = call("support-bot", "1", "issueRefund", REFUND);   // nobody answers: waits 3 s

        assertThat(result).hasStatus(403).bodyJson()
                .hasPathSatisfying("$.state", v -> v.assertThat().isEqualTo("EXPIRED"))
                .hasPathSatisfying("$.rule", v -> v.assertThat().isEqualTo("approval-expired"));
        UUID id = approvals.findAll().getFirst().getId();
        assertThat(approvals.findById(id).orElseThrow().getState()).isEqualTo(ApprovalState.EXPIRED);
        assertThat(received).isEmpty();
        // Too late to approve now.
        assertThat(approverPost(id, "approve", "{}")).hasStatus(409);
    }

    @Test
    void editedArgumentsThatBreakThePolicyAreBlocked() {
        CompletableFuture<MvcTestResult> agent = agentCalls(REFUND);   // 100 BDT: allowed by policy
        UUID id = awaitPending();

        // The approver "edits" the refund to 50,000 BDT. The policy (≤ 2000) must still apply.
        assertThat(approverPost(id, "edit",
                "{\"arguments\": {\"orderId\": 1001, \"amount\": 50000, \"reason\": \"damaged\"}}")).hasStatusOk();

        assertThat(agent.join()).hasStatus(403).bodyJson()
                .hasPathSatisfying("$.state", v -> v.assertThat().isEqualTo("APPROVED_WITH_EDITS"))
                .hasPathSatisfying("$.approvalId", v -> v.assertThat().isEqualTo(id.toString()));
        assertThat(received).isEmpty();   // never forwarded
    }

    @Test
    void editedArgumentsThatBreakTheSchemaAreBlocked() {
        CompletableFuture<MvcTestResult> agent = agentCalls(REFUND);
        UUID id = awaitPending();

        assertThat(approverPost(id, "edit",
                "{\"arguments\": {\"orderId\": 1001, \"amount\": 100, \"reason\": \"x\", \"sneaky\": true}}")).hasStatusOk();

        // Unknown argument, caught by the schema guard again. Same clear result shape as every other outcome.
        assertThat(agent.join()).hasStatus(403).bodyJson()
                .hasPathSatisfying("$.approvalId", v -> v.assertThat().isEqualTo(id.toString()))
                .hasPathSatisfying("$.state", v -> v.assertThat().isEqualTo("APPROVED_WITH_EDITS"))
                .hasPathSatisfying("$.rule", v -> v.assertThat().isEqualTo("approval-edit-invalid"))
                .hasPathSatisfying("$.problems[0]", v -> v.assertThat().asString().contains("sneaky"));
        assertThat(received).isEmpty();
    }

    @Test
    void validEditsAreForwardedWithTheEditedArguments() {
        CompletableFuture<MvcTestResult> agent = agentCalls(REFUND);
        UUID id = awaitPending();

        assertThat(approverPost(id, "edit",
                "{\"arguments\": {\"orderId\": 1001, \"amount\": 40, \"reason\": \"damaged\"}}")).hasStatusOk();

        assertThat(agent.join()).hasStatus(201);
        assertThat(received).singleElement().satisfies(r -> assertThat(r.body()).contains("\"amount\":40"));
    }

    @Test
    void killSwitchCancelsThePendingRequestAndTheAgentIsAnsweredAtOnce() {
        CompletableFuture<MvcTestResult> agent = agentCalls(REFUND);
        UUID id = awaitPending();

        assertThat(setKillSwitch("/api/admin/kill-switch/tools/issueRefund", true)).hasStatusOk();

        assertThat(agent.join()).hasStatus(403).bodyJson()
                .hasPathSatisfying("$.state", v -> v.assertThat().isEqualTo("CANCELLED"))
                .hasPathSatisfying("$.rule", v -> v.assertThat().isEqualTo("approval-cancelled"));
        assertThat(approvals.findById(id).orElseThrow().getState()).isEqualTo(ApprovalState.CANCELLED);
        assertThat(approverPost(id, "approve", "{}")).hasStatus(409);
    }

    @Test
    void allWritesKillSwitchAlsoCancelsPendingWriteApprovals() {
        CompletableFuture<MvcTestResult> agent = agentCalls(REFUND);
        UUID id = awaitPending();

        assertThat(setKillSwitch("/api/admin/kill-switch/all-writes", true)).hasStatusOk();

        assertThat(agent.join()).hasStatus(403);
        assertThat(approvals.findById(id).orElseThrow().getState()).isEqualTo(ApprovalState.CANCELLED);
    }

    @Test
    void anAgentTokenCannotUseTheApproverApi() {
        CompletableFuture<MvcTestResult> agent = agentCalls(REFUND);
        UUID id = awaitPending();
        String agentToken = tokenFor("support-bot");

        MvcTestResult selfApprove = mvc.post().uri("/api/approvals/{id}/approve", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + agentToken)
                .contentType(MediaType.APPLICATION_JSON).content("{}").exchange();
        assertThat(selfApprove).hasStatus(401);
        assertThat(mvc.get().uri("/api/approvals").header(HttpHeaders.AUTHORIZATION, "Bearer " + agentToken))
                .hasStatus(401);
        // The admin is not an approver either.
        assertThat(mvc.post().uri("/api/approvals/{id}/approve", id).with(admin())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).hasStatus(403);
        assertThat(approvals.findById(id).orElseThrow().getState()).isEqualTo(ApprovalState.PENDING);

        approverPost(id, "reject", "{\"reason\": \"test over\"}");   // release the held call
        agent.join();
    }

    @Test
    void approverCanListPendingRequests() {
        CompletableFuture<MvcTestResult> agent = agentCalls(REFUND);
        UUID id = awaitPending();

        assertThat(mvc.get().uri("/api/approvals?state=PENDING").with(approver())).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.content[0].id", v -> v.assertThat().isEqualTo(id.toString()))
                .hasPathSatisfying("$.content[0].toolName", v -> v.assertThat().isEqualTo("issueRefund"));
        assertThat(mvc.get().uri("/api/approvals/{id}", id).with(approver())).hasStatusOk();

        approverPost(id, "reject", "{\"reason\": \"test over\"}");
        agent.join();
    }

    // ---------- helpers ----------

    private CompletableFuture<MvcTestResult> agentCalls(String arguments) {
        return CompletableFuture.supplyAsync(() -> call("support-bot", "1", "issueRefund", arguments));
    }

    /** Waits until the held call has been saved as a PENDING request, and returns its id. */
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

    private MvcTestResult approverPost(UUID id, String action, String body) {
        return mvc.post().uri("/api/approvals/{id}/{action}", id, action).with(approver())
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor approver() {
        return httpBasic("approver", "approver-local-only");
    }
}
