package com.agentgate.gateway.approval;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record ApprovalResponse(UUID id, String agentId, String endUserId, String toolName, JsonNode originalArguments,
                               JsonNode editedArguments, ApprovalState state, String approver, String reason,
                               Instant createdAt, Instant decidedAt, Instant expiresAt) {
}
