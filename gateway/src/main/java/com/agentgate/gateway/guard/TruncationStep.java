package com.agentgate.gateway.guard;

import com.agentgate.gateway.pipeline.CallChain;
import com.agentgate.gateway.pipeline.CallOutcome;
import com.agentgate.gateway.pipeline.CallStep;
import com.agentgate.gateway.pipeline.CallStepOrder;
import com.agentgate.gateway.pipeline.ToolCall;
import java.nio.charset.StandardCharsets;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * FR5 guard, the OUTERMOST result guard: a result bigger than the limit is cut, and the agent gets a notice
 * with the original size. Protects the agent's context window and the gateway's memory.
 */
@Component
@Order(CallStepOrder.TRUNCATION)
public class TruncationStep implements CallStep {

    private final GuardProperties properties;
    private final ObjectMapper objectMapper;

    public TruncationStep(GuardProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public CallOutcome handle(ToolCall call, CallChain chain) {
        CallOutcome outcome = chain.proceed(call);
        if (!(outcome instanceof CallOutcome.Completed completed) || completed.body() == null) {
            return outcome;
        }

        long limit = properties.truncation().maxBytes();
        byte[] json = objectMapper.writeValueAsString(completed.body()).getBytes(StandardCharsets.UTF_8);
        if (json.length <= limit) {
            return outcome;
        }

        // Cut by bytes. A multi-byte character cut in half shows up as U+FFFD, which is fine for a preview.
        String partial = new String(json, 0, (int) limit, StandardCharsets.UTF_8);
        ObjectNode body = objectMapper.createObjectNode();
        body.put("truncated", true);
        body.put("notice", "Result truncated: the original result was " + json.length + " bytes and the limit is "
                + limit + " bytes. Only the beginning is shown.");
        body.put("originalSizeBytes", json.length);
        body.put("limitBytes", limit);
        body.put("partialResult", partial);
        return new CallOutcome.Completed(completed.status(), body);
    }
}
