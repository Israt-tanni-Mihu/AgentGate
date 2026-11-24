package com.agentgate.gateway.audit;

import java.time.Instant;
import java.util.UUID;

/** Everything about one audit row except the two hashes. These are exactly the values the hash is made of. */
record AuditFields(long seq, UUID callId, Instant occurredAt, String agentId, String endUserId, String toolName,
                   String arguments, String decision, String decidedByStep, String ruleId, Integer policyVersion,
                   UUID approvalId, String approvalState, String approver, String editedArguments, long latencyMs,
                   int httpStatus, String reason) {
}
