package com.agentgate.gateway.guard;

import com.agentgate.gateway.guard.redaction.DataRedactor;
import com.agentgate.gateway.guard.redaction.SensitiveDataType;
import com.agentgate.gateway.identity.Clearance;
import com.agentgate.gateway.pipeline.CallChain;
import com.agentgate.gateway.pipeline.CallOutcome;
import com.agentgate.gateway.pipeline.CallStep;
import com.agentgate.gateway.pipeline.CallStepOrder;
import com.agentgate.gateway.pipeline.ToolCall;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * FR5 guard, runs after the tool answered: masks personal data in the result according to the agent's
 * clearance. Which data types are hidden comes from {@link GuardProperties} (configuration, not code).
 *
 * It walks the whole JSON tree and masks every string value; numbers and booleans are never touched.
 */
@Component
@Order(CallStepOrder.REDACTION)
public class RedactionStep implements CallStep {

    private final GuardProperties properties;
    private final ObjectMapper objectMapper;
    private final Map<SensitiveDataType, DataRedactor> redactors = new EnumMap<>(SensitiveDataType.class);

    public RedactionStep(List<DataRedactor> redactors, GuardProperties properties, ObjectMapper objectMapper) {
        for (DataRedactor redactor : redactors) {
            this.redactors.put(redactor.type(), redactor);
        }
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public CallOutcome handle(ToolCall call, CallChain chain) {
        CallOutcome outcome = chain.proceed(call);
        if (!(outcome instanceof CallOutcome.Completed completed) || completed.body() == null) {
            return outcome;
        }
        List<DataRedactor> active = activeRedactors(call.context().clearance());
        if (active.isEmpty()) {
            return outcome;
        }
        return new CallOutcome.Completed(completed.status(), redact(completed.body(), active));
    }

    /** Redactors for this clearance, in the enum order. A clearance without a rule sees nothing (fail closed). */
    private List<DataRedactor> activeRedactors(Clearance clearance) {
        Set<SensitiveDataType> hidden = properties.redactionByClearance()
                .getOrDefault(clearance, EnumSet.allOf(SensitiveDataType.class));
        List<DataRedactor> active = new ArrayList<>();
        for (SensitiveDataType type : SensitiveDataType.values()) {
            if (hidden.contains(type) && redactors.containsKey(type)) {
                active.add(redactors.get(type));
            }
        }
        return active;
    }

    /** Returns a masked COPY of the tree; the original tree is not changed. */
    private JsonNode redact(JsonNode node, List<DataRedactor> active) {
        if (node.isObject()) {
            ObjectNode copy = objectMapper.createObjectNode();
            for (var field : node.properties()) {
                copy.set(field.getKey(), redact(field.getValue(), active));
            }
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = objectMapper.createArrayNode();
            for (JsonNode item : node) {
                copy.add(redact(item, active));
            }
            return copy;
        }
        if (node.isString()) {
            String text = node.asString();
            for (DataRedactor redactor : active) {
                text = redactor.redact(text);
            }
            return objectMapper.getNodeFactory().stringNode(text);
        }
        return node;
    }
}
