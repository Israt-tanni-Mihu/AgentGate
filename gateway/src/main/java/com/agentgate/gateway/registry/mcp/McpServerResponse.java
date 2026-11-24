package com.agentgate.gateway.registry.mcp;

import com.agentgate.gateway.registry.RiskLevel;
import java.time.Instant;

public record McpServerResponse(Long id, String name, String url, String ownerTeam, RiskLevel defaultRiskLevel,
                                Instant createdAt) {

    static McpServerResponse from(McpServer server) {
        return new McpServerResponse(server.getId(), server.getName(), server.getUrl(), server.getOwnerTeam(),
                server.getDefaultRiskLevel(), server.getCreatedAt());
    }
}
