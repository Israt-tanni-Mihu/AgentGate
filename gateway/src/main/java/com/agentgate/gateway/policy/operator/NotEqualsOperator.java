package com.agentgate.gateway.policy.operator;

import com.agentgate.gateway.policy.PolicyInput;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** argument != value. A MISSING argument is not "different": it returns false (fail closed). */
@Component
public class NotEqualsOperator implements ConditionOperator {

    @Override
    public String name() {
        return "notEquals";
    }

    @Override
    public boolean test(JsonNode actual, JsonNode expected, PolicyInput input) {
        return !JsonValues.isAbsent(actual) && actual.isValueNode() && !JsonValues.same(actual, expected);
    }
}
