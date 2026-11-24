package com.agentgate.gateway.catalog;

import com.agentgate.gateway.registry.AccessMode;
import com.agentgate.gateway.registry.RiskLevel;
import com.agentgate.gateway.registry.ToolResponse;
import tools.jackson.databind.JsonNode;

/** What an agent may know about a tool: how to call it, not where it lives or who owns it. */
public record AgentToolResponse(String name, String description, JsonNode inputSchema,
                                RiskLevel riskLevel, AccessMode accessMode) {

    static AgentToolResponse of(ToolResponse tool) {
        return new AgentToolResponse(tool.name(), tool.description(), tool.inputSchema(),
                tool.riskLevel(), tool.accessMode());
    }
}
