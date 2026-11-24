package com.agentgate.gateway.approval;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One held tool call (table approval_requests, Flyway V5). Every state change goes through the state objects. */
@Entity
@Table(name = "approval_requests")
public class ApprovalRequest {

    @Id
    private UUID id;

    @Column(name = "agent_id", nullable = false, length = 64)
    private String agentId;

    @Column(name = "end_user_id", length = 100)
    private String endUserId;

    @Column(name = "tool_name", nullable = false, length = 64)
    private String toolName;

    /** JSON text in a jsonb column (same approach as Tool.inputSchema). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "original_arguments", nullable = false)
    private String originalArguments;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "edited_arguments")
    private String editedArguments;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ApprovalState state;

    @Column(length = 100)
    private String approver;

    @Column(length = 500)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected ApprovalRequest() {
    }

    public ApprovalRequest(UUID id, String agentId, String endUserId, String toolName, String originalArguments,
                           Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.agentId = agentId;
        this.endUserId = endUserId;
        this.toolName = toolName;
        this.originalArguments = originalArguments;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.state = ApprovalState.PENDING;
    }

    // Transitions: the state object decides if it is legal (or throws); we only record who, when and why.

    public void approve(String approver, Instant now) {
        decide(state.behavior().approve(), approver, null, now);
    }

    public void approveWithEdits(String approver, String newArguments, Instant now) {
        decide(state.behavior().approveWithEdits(), approver, null, now);
        this.editedArguments = newArguments;
    }

    public void reject(String approver, String reason, Instant now) {
        decide(state.behavior().reject(), approver, reason, now);
    }

    public void expire(Instant now) {
        decide(state.behavior().expire(), null, "no answer before the timeout", now);
    }

    public void cancel(String why, Instant now) {
        decide(state.behavior().cancel(), null, why, now);
    }

    private void decide(ApprovalState next, String approver, String reason, Instant now) {
        this.state = next;
        this.approver = approver;
        this.reason = reason;
        this.decidedAt = now;
    }

    public UUID getId() { return id; }
    public String getAgentId() { return agentId; }
    public String getEndUserId() { return endUserId; }
    public String getToolName() { return toolName; }
    public String getOriginalArguments() { return originalArguments; }
    public String getEditedArguments() { return editedArguments; }
    public ApprovalState getState() { return state; }
    public String getApprover() { return approver; }
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDecidedAt() { return decidedAt; }
    public Instant getExpiresAt() { return expiresAt; }
}
