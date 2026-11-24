package com.agentgate.gateway.policy;

import com.agentgate.gateway.pipeline.ToolCall;
import tools.jackson.databind.JsonNode;

/**
 * Everything a policy may look at. Kept separate from ToolCall so the playground (FR9)
 * can evaluate a sample call without a real tool call.
 */
public record PolicyInput(String agentId, String endUserId, String toolName, JsonNode arguments) {

    public static PolicyInput from(ToolCall call) {
        return new PolicyInput(call.context().agentId(), call.context().endUserId(), call.toolName(), call.arguments());
    }

    /** The named argument, or a MissingNode if the agent did not send it. */
    public JsonNode argument(String name) {
        return arguments.path(name);
    }
}
