package com.agentgate.gateway.approval;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Published when a call is held. Listeners: the webhook notifier now, the audit log (FR7) later. */
public record ApprovalRequested(UUID approvalId, String agentId, String endUserId, String toolName,
                                JsonNode arguments, Instant expiresAt) {
}
