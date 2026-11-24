package com.agentgate.gateway.policy;

import java.util.List;

/**
 * The decision PLUS a trace of how the engine reached it: one {@link RuleCheck} per rule, in rule order.
 * The live pipeline only needs {@link #decision()}; the playground (FR9) shows the checks too.
 */
public record PolicyEvaluation(PolicyDecision decision, List<RuleCheck> checks) {

    public PolicyEvaluation {
        checks = List.copyOf(checks);
    }

    /** What happened to one rule during evaluation. */
    public record RuleCheck(String ruleId, PolicyRule.Effect effect, Result result, String detail) {
    }

    public enum Result {
        /** The rule's agents/tools do not include this call, so it was skipped. */
        NOT_FOR_THIS_CALL,
        /** The rule is for this agent and tool, but one of its conditions is false. */
        CONDITION_FAILED,
        /** The rule is for this call and all its conditions are true. */
        MATCHED,
        /** Not looked at: a DENY rule had already decided the call. */
        NOT_CHECKED
    }
}
