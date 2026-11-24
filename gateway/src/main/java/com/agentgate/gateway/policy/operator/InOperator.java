package com.agentgate.gateway.policy.operator;

import com.agentgate.gateway.policy.PolicyInput;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** argument is one of the values in a list, e.g. status in ["SHIPPED", "DELIVERED"]. */
@Component
public class InOperator implements ConditionOperator {

    @Override
    public String name() {
        return "in";
    }

    @Override
    public boolean test(JsonNode actual, JsonNode expected, PolicyInput input) {
        if (expected == null || !expected.isArray()) {
            return false;
        }
        for (JsonNode option : expected) {
            if (JsonValues.same(actual, option)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String checkValue(JsonNode expected) {
        return expected == null || !expected.isArray() || expected.isEmpty() ? "needs a non-empty list" : null;
    }
}
