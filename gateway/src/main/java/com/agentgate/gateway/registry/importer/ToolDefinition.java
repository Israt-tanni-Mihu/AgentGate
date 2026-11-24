package com.agentgate.gateway.registry.importer;

import com.agentgate.gateway.registry.RiskLevel;
import tools.jackson.databind.JsonNode;

/** A REST tool described in a format-neutral way: what every {@link ToolSpecAdapter} must produce. */
public record ToolDefinition(
        String name,
        String description,
        JsonNode inputSchema,
        RiskLevel suggestedRiskLevel,
        String httpMethod,
        String path) {
}
