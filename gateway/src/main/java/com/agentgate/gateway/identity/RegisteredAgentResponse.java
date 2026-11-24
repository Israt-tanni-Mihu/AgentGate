package com.agentgate.gateway.identity;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** Returned only by POST /api/admin/agents: the one and only time the plain secret is shown. */
public record RegisteredAgentResponse(
        Long id,
        String clientId,
        String displayName,
        Clearance clearance,
        boolean enabled,
        Instant createdAt,
        @Schema(description = "Shown ONCE. Only a hash is stored, so it cannot be shown again. Copy it now.")
        String clientSecret) {
}
