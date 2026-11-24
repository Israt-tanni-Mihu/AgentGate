package com.agentgate.gateway.admin;

import com.agentgate.gateway.pipeline.CallChain;
import com.agentgate.gateway.pipeline.CallOutcome;
import com.agentgate.gateway.pipeline.CallStep;
import com.agentgate.gateway.pipeline.CallStepOrder;
import com.agentgate.gateway.pipeline.ToolCall;
import com.agentgate.gateway.registry.AccessMode;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * FR8 step, and the FIRST step of the pipeline (order 100). A disabled agent or tool must never reach a rate
 * counter, a policy or a downstream system, and an emergency stop must not wait for anything else.
 * It reads only the in-memory state, so it adds almost no time to a call.
 */
@Component
@Order(CallStepOrder.KILL_SWITCH)
public class KillSwitchStep implements CallStep {

    private final KillSwitchState state;

    public KillSwitchStep(KillSwitchState state) {
        this.state = state;
    }

    @Override
    public CallOutcome handle(ToolCall call, CallChain chain) {
        String agentId = call.context().agentId();
        if (state.isAgentKilled(agentId)) {
            return new CallOutcome.Denied("kill-switch-agent",
                    "agent '" + agentId + "' is disabled by an administrator", Map.of());
        }
        if (state.isToolKilled(call.toolName())) {
            return new CallOutcome.Denied("kill-switch-tool",
                    "tool '" + call.toolName() + "' is disabled by an administrator", Map.of());
        }
        if (state.areAllWritesKilled() && call.tool().accessMode() == AccessMode.WRITE) {
            return new CallOutcome.Denied("kill-switch-all-writes",
                    "all write tools are disabled by an administrator", Map.of());
        }
        return chain.proceed(call);
    }
}
