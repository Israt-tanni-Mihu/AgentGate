package com.agentgate.gateway.pipeline;

import com.agentgate.gateway.registry.ToolKind;

/**
 * STRATEGY (see docs/patterns.md): HOW the gateway calls one kind of tool.
 * {@link RestToolInvoker} makes an HTTP call; {@link McpToolInvoker} sends an MCP tools/call.
 * {@link ForwardStep} picks the strategy by {@link ToolKind}. A third kind of tool = a new implementation,
 * no change to the pipeline or to the circuit breaker around it.
 */
public interface ToolInvoker {

    ToolKind kind();

    /**
     * Calls the tool. A broken or unreachable tool throws {@link ToolCallFailedException}
     * (that is what the circuit breaker counts); anything the tool itself answers is a {@link CallOutcome.Completed}.
     */
    CallOutcome invoke(ToolCall call);
}
