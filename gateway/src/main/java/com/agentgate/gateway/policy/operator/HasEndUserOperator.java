package com.agentgate.gateway.policy.operator;

import com.agentgate.gateway.policy.PolicyInput;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * True only when the call carries an end user (X-On-Behalf-Of header). The argument named in the condition is ignored.
 * Why it exists (QA1 finding): the ownership guard can only check calls that name an end user, so an agent that
 * simply drops the header must not get customer data. A customer-facing rule adds this condition to say "only
 * while acting for a customer".
 */
@Component
public class HasEndUserOperator implements ConditionOperator {

    @Override
    public String name() {
        return "hasEndUser";
    }

    @Override
    public boolean test(JsonNode actual, JsonNode expected, PolicyInput input) {
        return input.endUserId() != null;
    }

    @Override
    public String checkValue(JsonNode expected) {
        return JsonValues.isAbsent(expected) ? null : "takes no value";
    }

    @Override
    public String explainFailure(String argument, JsonNode actual, JsonNode expected, PolicyInput input) {
        return "the call must name the end user it acts for (X-On-Behalf-Of header)";
    }
}
