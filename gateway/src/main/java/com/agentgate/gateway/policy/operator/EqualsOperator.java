package com.agentgate.gateway.policy.operator;

import com.agentgate.gateway.policy.PolicyInput;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** argument == value (numbers by value: 1 equals 1.0 equals "1"). */
@Component
public class EqualsOperator implements ConditionOperator {

    @Override
    public String name() {
        return "equals";
    }

    @Override
    public boolean test(JsonNode actual, JsonNode expected, PolicyInput input) {
        return JsonValues.same(actual, expected);
    }
}
