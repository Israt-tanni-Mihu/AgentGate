package com.agentgate.gateway.pipeline;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Notes about ONE call, collected while it travels through the pipeline, so the audit log (FR7) can say
 * "which rule decided" and "what the approver did" without every step knowing about auditing.
 *
 * The trace is bound to the call with a ScopedValue (Java 25: like a ThreadLocal that cleans up by itself).
 * A call stays on one (virtual) thread, even while it waits for approval. The static record* methods do
 * nothing when no call is running (unit tests of single steps), so steps can call them without checks.
 */
public final class CallTrace {

    static final ScopedValue<CallTrace> CURRENT = ScopedValue.newInstance();

    private final UUID callId;

    private String decidingStep;
    private String policyRuleId;
    private Integer policyVersion;
    private UUID approvalId;
    private String approvalState;
    private String approver;
    private JsonNode editedArguments;

    CallTrace(UUID callId) {
        this.callId = callId;
    }

    /** Called by PolicyStep after every evaluation (allow, deny, dry-run). The last evaluation wins (approval edits). */
    public static void recordPolicy(String ruleId, int version) {
        if (CURRENT.isBound()) {
            CURRENT.get().policyRuleId = ruleId;
            CURRENT.get().policyVersion = version;
        }
    }

    /** Called by ApprovalStep once a human (or the timeout, or the kill switch) has decided. */
    public static void recordApproval(UUID approvalId, String state, String approver, JsonNode editedArguments) {
        if (CURRENT.isBound()) {
            CallTrace trace = CURRENT.get();
            trace.approvalId = approvalId;
            trace.approvalState = state;
            trace.approver = approver;
            trace.editedArguments = editedArguments;
        }
    }

    /** The first step that stops a call is the one that decided; outer steps passing the result up do not change it. */
    void markDecidedBy(String stepName) {
        if (decidingStep == null) {
            decidingStep = stepName;
        }
    }

    UUID callId() {
        return callId;
    }

    String decidingStep() {
        return decidingStep;
    }

    String policyRuleId() {
        return policyRuleId;
    }

    Integer policyVersion() {
        return policyVersion;
    }

    UUID approvalId() {
        return approvalId;
    }

    String approvalState() {
        return approvalState;
    }

    String approver() {
        return approver;
    }

    JsonNode editedArguments() {
        return editedArguments;
    }
}
