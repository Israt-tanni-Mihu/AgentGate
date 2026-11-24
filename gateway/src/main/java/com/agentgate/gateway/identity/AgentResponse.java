package com.agentgate.gateway.identity;

import java.time.Instant;

/** What list/get/disable return. Never contains the secret or its hash. */
public record AgentResponse(
        Long id,
        String clientId,
        String displayName,
        Clearance clearance,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt) {
}
