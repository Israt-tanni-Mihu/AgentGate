package com.agentgate.gateway.policy;

import com.agentgate.gateway.policy.operator.ConditionOperator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Decides allow/deny for one call. Pure logic: no database, no HTTP, so it is easy to unit test
 * and the playground (FR9) can reuse it.
 *
 * How it decides:
 * 1. Look only at rules whose agents and tools match the call.
 * 2. A rule "matches" when ALL its conditions are true.
 * 3. If any matching rule is DENY → denied by that rule (deny beats allow).
 * 4. Else if any matching rule is ALLOW → allowed by the first one.
 * 5. Else → DEFAULT DENY.
 */
@Component
public class PolicyEngine {

    private final Map<String, ConditionOperator> operators = new HashMap<>();

    /** Spring passes every ConditionOperator bean; we index them by name. */
    public PolicyEngine(List<ConditionOperator> operatorBeans) {
        for (ConditionOperator operator : operatorBeans) {
            if (operators.put(operator.name(), operator) != null) {
                throw new IllegalStateException("Two operators are named '" + operator.name() + "'");
            }
        }
    }

    public Optional<ConditionOperator> operator(String name) {
        return Optional.ofNullable(operators.get(name));
    }

    public PolicyDecision evaluate(PolicySnapshot policy, PolicyInput input) {
        PolicyRule firstAllow = null;
        List<String> nearMisses = new ArrayList<>();

        for (PolicyRule rule : policy.rules()) {
            if (!rule.appliesTo(input.agentId(), input.toolName())) {
                continue;
            }
            String failure = firstFailedCondition(rule, input);
            if (failure != null) {
                if (rule.effect() == PolicyRule.Effect.ALLOW) {
                    nearMisses.add("rule '" + rule.id() + "' did not apply because " + failure);
                }
                continue;
            }
            if (rule.effect() == PolicyRule.Effect.DENY) {
                return new PolicyDecision(false, rule.id(), "Denied by rule '" + rule.id() + "'"
                        + (rule.description() == null ? "" : ": " + rule.description()), policy.version());
            }
            if (firstAllow == null) {
                firstAllow = rule;
            }
        }

        if (firstAllow != null) {
            return new PolicyDecision(true, firstAllow.id(), "Allowed by rule '" + firstAllow.id() + "'", policy.version());
        }
        String reason = "No rule allows agent '" + input.agentId() + "' to call '" + input.toolName() + "' (default deny)";
        if (!nearMisses.isEmpty()) {
            reason += ". " + String.join("; ", nearMisses);
        }
        return new PolicyDecision(false, PolicyDecision.DEFAULT_DENY, reason, policy.version());
    }

    /** Null if every condition holds; otherwise an explanation of the first one that does not. */
    private String firstFailedCondition(PolicyRule rule, PolicyInput input) {
        for (Condition condition : rule.conditions()) {
            ConditionOperator operator = operators.get(condition.operator());
            JsonNode actual = input.argument(condition.argument());
            if (operator == null) {
                return "unknown operator '" + condition.operator() + "'";   // fail closed
            }
            if (!operator.test(actual, condition.value(), input)) {
                return operator.explainFailure(condition.argument(), actual, condition.value(), input);
            }
        }
        return null;
    }
}
