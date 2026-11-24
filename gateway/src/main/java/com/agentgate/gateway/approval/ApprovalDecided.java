package com.agentgate.gateway.approval;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Published on every final decision (approved, edited, rejected, expired, cancelled). editedArguments may be null. */
public record ApprovalDecided(UUID approvalId, ApprovalState state, String agentId, String endUserId, String toolName,
                              String approver, String reason, JsonNode editedArguments, Instant decidedAt) {
}
