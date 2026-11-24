package com.agentgate.gateway.policy.operator;

import com.agentgate.gateway.policy.PolicyInput;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * argument == the end user the agent acts for (X-On-Behalf-Of header).
 * Blocks cross-customer reads: support-bot acting for customer 1 may not read customer 2.
 * No end user on the call → false.
 */
@Component
public class MatchesEndUserOperator implements ConditionOperator {

    @Override
    public String name() {
        return "matchesEndUser";
    }

    @Override
    public boolean test(JsonNode actual, JsonNode expected, PolicyInput input) {
        if (input.endUserId() == null) {
            return false;
        }
        return JsonValues.same(actual, JsonNodeFactory.instance.stringNode(input.endUserId()));
    }

    /** The rule compares with the end user, so it takes no value. */
    @Override
    public String checkValue(JsonNode expected) {
        return JsonValues.isAbsent(expected) ? null : "takes no value (it compares with X-On-Behalf-Of)";
    }

    @Override
    public String explainFailure(String argument, JsonNode actual, JsonNode expected, PolicyInput input) {
        String endUser = input.endUserId() == null ? "none (no X-On-Behalf-Of header)" : input.endUserId();
        return argument + " must match the end user (value: " + JsonValues.show(actual) + ", end user: " + endUser + ")";
    }
}
