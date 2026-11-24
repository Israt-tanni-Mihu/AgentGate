package com.agentgate.gateway.policy.operator;

import com.agentgate.gateway.policy.PolicyInput;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** argument >= value, for numbers only. A missing or non-numeric argument gives false. */
@Component
public class GreaterThanOrEqualOperator implements ConditionOperator {

    @Override
    public String name() {
        return "greaterThanOrEqual";
    }

    @Override
    public boolean test(JsonNode actual, JsonNode expected, PolicyInput input) {
        BigDecimal value = JsonValues.number(actual);
        BigDecimal limit = JsonValues.number(expected);
        return value != null && limit != null && value.compareTo(limit) >= 0;
    }

    @Override
    public String checkValue(JsonNode expected) {
        return expected == null || !expected.isNumber() ? "needs a number" : null;
    }
}
