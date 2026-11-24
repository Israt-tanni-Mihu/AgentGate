package com.agentgate.gateway.health;

import com.agentgate.gateway.pipeline.CallChain;
import com.agentgate.gateway.pipeline.CallOutcome;
import com.agentgate.gateway.pipeline.CallStep;
import com.agentgate.gateway.pipeline.CallStepOrder;
import com.agentgate.gateway.pipeline.ToolCall;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * FR10: early fail-fast. A tool with an OPEN breaker answers 503 right after the kill switch, so the call does not
 * use up rate limits and a HIGH-risk call is not sent to a human for approval when the tool is down anyway.
 * (HALF_OPEN passes: those are the trial calls.)
 */
@Component
@Order(CallStepOrder.TOOL_AVAILABILITY)
public class ToolAvailabilityStep implements CallStep {

    private final ToolBreakers breakers;

    public ToolAvailabilityStep(ToolBreakers breakers) {
        this.breakers = breakers;
    }

    @Override
    public CallOutcome handle(ToolCall call, CallChain chain) {
        if (breakers.isOpen(call.toolName())) {
            throw new ToolUnavailableException(call.toolName(), breakers.retryAfterSeconds());
        }
        return chain.proceed(call);
    }
}
