package com.agentgate.gateway.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * One row of the audit log (table audit_records, Flyway V6). {@code @Immutable}: Hibernate never issues an UPDATE
 * for it, and there are no setters. Rows are only ever appended by {@link AuditWriter}.
 */
@Entity
@Immutable
@Table(name = "audit_records")
public class AuditRecord {

    @Id
    private long seq;

    @Column(name = "call_id", nullable = false)
    private UUID callId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "agent_id", nullable = false, length = 64)
    private String agentId;

    @Column(name = "end_user_id", length = 100)
    private String endUserId;

    @Column(name = "tool_name", nullable = false)
    private String toolName;

    @Column(nullable = false)
    private String arguments;

    @Column(nullable = false, length = 16)
    private String decision;

    @Column(name = "decided_by_step", length = 64)
    private String decidedByStep;

    @Column(name = "rule_id")
    private String ruleId;

    @Column(name = "policy_version")
    private Integer policyVersion;

    @Column(name = "approval_id")
    private UUID approvalId;

    @Column(name = "approval_state", length = 24)
    private String approvalState;

    @Column(length = 100)
    private String approver;

    @Column(name = "edited_arguments")
    private String editedArguments;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    @Column(name = "http_status", nullable = false)
    private int httpStatus;

    @Column(length = 500)
    private String reason;

    @Column(name = "prev_hash", nullable = false, length = 64)
    private String prevHash;

    @Column(nullable = false, length = 64)
    private String hash;

    protected AuditRecord() {
    }

    AuditRecord(AuditFields f, String prevHash, String hash) {
        this.seq = f.seq();
        this.callId = f.callId();
        this.occurredAt = f.occurredAt();
        this.agentId = f.agentId();
        this.endUserId = f.endUserId();
        this.toolName = f.toolName();
        this.arguments = f.arguments();
        this.decision = f.decision();
        this.decidedByStep = f.decidedByStep();
        this.ruleId = f.ruleId();
        this.policyVersion = f.policyVersion();
        this.approvalId = f.approvalId();
        this.approvalState = f.approvalState();
        this.approver = f.approver();
        this.editedArguments = f.editedArguments();
        this.latencyMs = f.latencyMs();
        this.httpStatus = f.httpStatus();
        this.reason = f.reason();
        this.prevHash = prevHash;
        this.hash = hash;
    }

    public long getSeq() { return seq; }
    public UUID getCallId() { return callId; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getAgentId() { return agentId; }
    public String getEndUserId() { return endUserId; }
    public String getToolName() { return toolName; }
    public String getArguments() { return arguments; }
    public String getDecision() { return decision; }
    public String getDecidedByStep() { return decidedByStep; }
    public String getRuleId() { return ruleId; }
    public Integer getPolicyVersion() { return policyVersion; }
    public UUID getApprovalId() { return approvalId; }
    public String getApprovalState() { return approvalState; }
    public String getApprover() { return approver; }
    public String getEditedArguments() { return editedArguments; }
    public long getLatencyMs() { return latencyMs; }
    public int getHttpStatus() { return httpStatus; }
    public String getReason() { return reason; }
    public String getPrevHash() { return prevHash; }
    public String getHash() { return hash; }
}
