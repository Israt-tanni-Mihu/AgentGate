package com.agentgate.gateway.policy;

import com.agentgate.gateway.policy.PolicyEvaluation.Result;
import com.agentgate.gateway.policy.PolicyEvaluation.RuleCheck;
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

    /**
     * "Could this agent ever call this tool?", for the tool list. Arguments are not known yet, so a rule with
     * conditions is not judged here: the tool is listed when an ALLOW rule applies and no unconditional DENY does.
     * Dry-run lets every call through, so every tool is listed.
     */
    public boolean mayCall(PolicySnapshot policy, String agentId, String toolName) {
        if (policy.dryRun()) {
            return true;
        }
        boolean allowed = false;
        for (PolicyRule rule : policy.rules()) {
            if (!rule.appliesTo(agentId, toolName)) {
                continue;
            }
            if (rule.effect() == PolicyRule.Effect.DENY && rule.conditions().isEmpty()) {
                return false;
            }
            allowed |= rule.effect() == PolicyRule.Effect.ALLOW;
        }
        return allowed;
    }

    /** Used by the live pipeline (PolicyStep). Same code path as {@link #explain}, so both always agree. */
    public PolicyDecision evaluate(PolicySnapshot policy, PolicyInput input) {
        return explain(policy, input).decision();
    }

    /**
     * The ONE place where the decision is made. Besides the decision it records what happened to every
     * rule; the playground (FR9) shows that trace, the pipeline ignores it.
     */
    public PolicyEvaluation explain(PolicySnapshot policy, PolicyInput input) {
        PolicyRule firstAllow = null;
        List<String> nearMisses = new ArrayList<>();
        List<RuleCheck> checks = new ArrayList<>();
        List<PolicyRule> rules = policy.rules();

        for (int i = 0; i < rules.size(); i++) {
            PolicyRule rule = rules.get(i);
            if (!rule.appliesTo(input.agentId(), input.toolName())) {
                checks.add(new RuleCheck(rule.id(), rule.effect(), Result.NOT_FOR_THIS_CALL,
                        "rule is for agents " + rule.agents() + " and tools " + rule.tools()));
                continue;
            }
            String failure = firstFailedCondition(rule, input);
            if (failure != null) {
                checks.add(new RuleCheck(rule.id(), rule.effect(), Result.CONDITION_FAILED, failure));
                if (rule.effect() == PolicyRule.Effect.ALLOW) {
                    nearMisses.add("rule '" + rule.id() + "' did not apply because " + failure);
                }
                continue;
            }
            checks.add(new RuleCheck(rule.id(), rule.effect(), Result.MATCHED,
                    rule.conditions().isEmpty() ? "no conditions" : "all conditions are true"));
            if (rule.effect() == PolicyRule.Effect.DENY) {
                for (PolicyRule rest : rules.subList(i + 1, rules.size())) {
                    checks.add(new RuleCheck(rest.id(), rest.effect(), Result.NOT_CHECKED,
                            "a DENY rule already decided the call"));
                }
                PolicyDecision denied = new PolicyDecision(false, rule.id(), "Denied by rule '" + rule.id() + "'"
                        + (rule.description() == null ? "" : ": " + rule.description()), policy.version());
                return new PolicyEvaluation(denied, checks);
            }
            if (firstAllow == null) {
                firstAllow = rule;
            }
        }

        if (firstAllow != null) {
            return new PolicyEvaluation(new PolicyDecision(true, firstAllow.id(),
                    "Allowed by rule '" + firstAllow.id() + "'", policy.version()), checks);
        }
        String reason = "No rule allows agent '" + input.agentId() + "' to call '" + input.toolName() + "' (default deny)";
        if (!nearMisses.isEmpty()) {
            reason += ". " + String.join("; ", nearMisses);
        }
        return new PolicyEvaluation(new PolicyDecision(false, PolicyDecision.DEFAULT_DENY, reason, policy.version()), checks);
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
