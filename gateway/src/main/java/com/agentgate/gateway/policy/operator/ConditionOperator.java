package com.agentgate.gateway.policy.operator;

import com.agentgate.gateway.policy.PolicyInput;
import tools.jackson.databind.JsonNode;

/**
 * STRATEGY (see docs/patterns.md): one way of comparing an argument with a value.
 * Each operator is a Spring bean; the PolicyEngine picks one by {@link #name()}.
 * A new operator (e.g. "matches regex") = one new class, no change to the engine.
 *
 * Safety rule for every operator: if the argument is missing or has the wrong type, return false.
 * For an ALLOW rule that means "not allowed", so mistakes fail closed.
 */
public interface ConditionOperator {

    /** The name used in policy JSON, e.g. "lessThanOrEqual". */
    String name();

    boolean test(JsonNode actual, JsonNode expected, PolicyInput input);

    /** Checked when a draft is created. Returns a problem description, or null if the value is fine. */
    default String checkValue(JsonNode expected) {
        return JsonValues.isAbsent(expected) || !expected.isValueNode() ? "needs a simple value" : null;
    }

    /** Human-readable reason, used when a condition fails (shown to the agent and later in the audit log). */
    default String explainFailure(String argument, JsonNode actual, JsonNode expected, PolicyInput input) {
        return argument + " " + name() + " " + expected + " is false (value: " + JsonValues.show(actual) + ")";
    }
}
