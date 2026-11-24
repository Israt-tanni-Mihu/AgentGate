package com.agentgate.gateway.pipeline;

import com.agentgate.gateway.identity.CallContext;
import com.agentgate.gateway.registry.ToolResponse;
import tools.jackson.databind.node.ObjectNode;

/**
 * One tool call travelling through the pipeline: which tool, with which arguments, by whom.
 * Immutable: a step that wants to change the arguments (e.g. an approver editing them, FR4)
 * passes a NEW ToolCall to the next step.
 */
public record ToolCall(ToolResponse tool, ObjectNode arguments, CallContext context) {

    public String toolName() {
        return tool.name();
    }
}
