package com.agentgate.gateway.audit;

import java.time.Instant;
import java.util.UUID;

/** What the admin API returns for one audit record (arguments are already redacted in the stored row). */
public record AuditRecordResponse(long seq, UUID callId, Instant occurredAt, String agentId, String endUserId,
                                  String toolName, String arguments, String decision, String decidedByStep,
                                  String ruleId, Integer policyVersion, UUID approvalId, String approvalState,
                                  String approver, String editedArguments, long latencyMs, int httpStatus,
                                  String reason, String prevHash, String hash) {

    static AuditRecordResponse from(AuditRecord r) {
        return new AuditRecordResponse(r.getSeq(), r.getCallId(), r.getOccurredAt(), r.getAgentId(), r.getEndUserId(),
                r.getToolName(), r.getArguments(), r.getDecision(), r.getDecidedByStep(), r.getRuleId(),
                r.getPolicyVersion(), r.getApprovalId(), r.getApprovalState(), r.getApprover(),
                r.getEditedArguments(), r.getLatencyMs(), r.getHttpStatus(), r.getReason(), r.getPrevHash(),
                r.getHash());
    }
}
