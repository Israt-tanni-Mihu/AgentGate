package com.agentgate.gateway.registry;

import java.time.Instant;
import tools.jackson.databind.JsonNode;

/** What the API returns. We never return the JPA entity itself, so the DB model can change freely. */
public record ToolResponse(
        Long id,
        String name,
        String description,
        JsonNode inputSchema,
        RiskLevel riskLevel,
        String ownerTeam,
        ToolKind kind,
        String httpMethod,
        String baseUrl,
        String path,
        boolean enabled,
        AccessMode accessMode,
        Integer timeoutMs,
        Instant createdAt,
        Instant updatedAt) {
}
