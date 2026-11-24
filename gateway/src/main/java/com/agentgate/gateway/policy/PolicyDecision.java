package com.agentgate.gateway.policy;

/**
 * The answer of the policy engine. {@code ruleId} is ALWAYS set: the rule that decided,
 * or {@link #DEFAULT_DENY} when no rule allowed the call.
 */
public record PolicyDecision(boolean allowed, String ruleId, String reason, int policyVersion) {

    public static final String DEFAULT_DENY = "default-deny";
}
