package com.agentgate.gateway.approval;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentgate.gateway.AgentCallIntegrationTest;
import com.agentgate.gateway.audit.AuditVerification;
import com.agentgate.gateway.audit.AuditVerifier;
import com.agentgate.gateway.audit.AuditWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ADR-0001 fix: PENDING rows left by a previous gateway run (no waiting call any more) are cancelled at startup,
 * through the State pattern, with an audit record. Decided rows and rows with a live waiter are not touched.
 */
class ApprovalRestartSweepIntegrationTest extends AgentCallIntegrationTest {

    private static final String ARGS = "{\"orderId\": 1001, \"amount\": 100, \"reason\": \"damaged\"}";

    @Autowired ApprovalRequestRepository approvals;
    @Autowired ApprovalRestartSweep sweep;
    @Autowired ApprovalWaiters waiters;
    @Autowired AuditWriter auditWriter;
    @Autowired AuditVerifier auditVerifier;
    @Autowired JdbcTemplate jdbc;
    @Autowired ConfigurableApplicationContext context;

    @BeforeEach
    void clearApprovals() {
        approvals.deleteAll();
    }

    /** A row as the old process left it: saved, but its waiting call died with the process. */
    private UUID orphanedPending() {
        Instant created = Instant.now().minusSeconds(30);
        ApprovalRequest request = new ApprovalRequest(UUID.randomUUID(), "support-bot", "1", "issueRefund", ARGS,
                created, created.plus(Duration.ofMinutes(2)));
        return approvals.save(request).getId();
    }

    @Test
    void startupCancelsOrphanedPendingApprovalsAndAuditsEachOne() {
        UUID first = orphanedPending();
        UUID second = orphanedPending();

        // The real trigger: the ApplicationReadyEvent that Spring Boot publishes when the gateway has started.
        context.publishEvent(new ApplicationReadyEvent(new SpringApplication(), new String[0], context, Duration.ZERO));

        for (UUID id : List.of(first, second)) {
            ApprovalRequest swept = approvals.findById(id).orElseThrow();
            assertThat(swept.getState()).isEqualTo(ApprovalState.CANCELLED);
            assertThat(swept.getReason()).isEqualTo(ApprovalRestartSweep.REASON);
            assertThat(swept.getDecidedAt()).isNotNull();
        }

        auditWriter.flush();
        for (UUID id : List.of(first, second)) {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT decision, decided_by_step, rule_id, approval_state, agent_id, tool_name, reason "
                            + "FROM audit_records WHERE approval_id = ?", id);
            assertThat(row).containsEntry("decision", "DENIED")
                    .containsEntry("decided_by_step", "ApprovalRestartSweep")
                    .containsEntry("rule_id", "approval-cancelled")
                    .containsEntry("approval_state", "CANCELLED")
                    .containsEntry("agent_id", "support-bot")
                    .containsEntry("tool_name", "issueRefund")
                    .containsEntry("reason", ApprovalRestartSweep.REASON);
        }
        assertThat(auditVerifier.verify().status()).isEqualTo(AuditVerification.Status.OK);
    }

    @Test
    void alreadyDecidedApprovalsAreNotTouched() {
        UUID approvedId = orphanedPending();
        UUID rejectedId = orphanedPending();
        Instant decided = Instant.now().minusSeconds(10);
        ApprovalRequest approved = approvals.findById(approvedId).orElseThrow();
        approved.approve("approver", decided);
        approvals.save(approved);
        ApprovalRequest rejected = approvals.findById(rejectedId).orElseThrow();
        rejected.reject("approver", "not verified", decided);
        approvals.save(rejected);

        assertThat(sweep.sweep()).isZero();

        ApprovalRequest approvedAfter = approvals.findById(approvedId).orElseThrow();
        assertThat(approvedAfter.getState()).isEqualTo(ApprovalState.APPROVED);
        assertThat(approvedAfter.getReason()).isNull();
        ApprovalRequest rejectedAfter = approvals.findById(rejectedId).orElseThrow();
        assertThat(rejectedAfter.getState()).isEqualTo(ApprovalState.REJECTED);
        assertThat(rejectedAfter.getReason()).isEqualTo("not verified");
        auditWriter.flush();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_records WHERE approval_id IN (?, ?)",
                Long.class, approvedId, rejectedId)).isZero();
    }

    @Test
    void aRequestWithALiveWaitingCallIsNotSwept() {
        UUID live = orphanedPending();
        waiters.register(live);   // a call that arrived after this start is holding it
        try {
            assertThat(sweep.sweep()).isZero();
            assertThat(approvals.findById(live).orElseThrow().getState()).isEqualTo(ApprovalState.PENDING);
        } finally {
            waiters.remove(live);
        }
    }
}
