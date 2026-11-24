package com.agentgate.gateway.pipeline;

import com.agentgate.gateway.registry.ToolKind;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Last step: forwards the call with the {@link ToolInvoker} that matches the tool's kind (REST or MCP).
 *
 * Not a bean on its own: {@code health.HealthConfig} wraps it with the timeout and circuit breaker (FR10),
 * so both kinds of tool get the same protection.
 */
public class ForwardStep implements CallStep {

    private final Map<ToolKind, ToolInvoker> invokers = new EnumMap<>(ToolKind.class);

    public ForwardStep(List<ToolInvoker> invokerList) {
        invokerList.forEach(invoker -> invokers.put(invoker.kind(), invoker));
    }

    @Override
    public CallOutcome handle(ToolCall call, CallChain chain) {
        ToolInvoker invoker = invokers.get(call.tool().kind());
        if (invoker == null) {
            throw new ToolNotForwardableException(call.tool().name());
        }
        return invoker.invoke(call);
    }
}
