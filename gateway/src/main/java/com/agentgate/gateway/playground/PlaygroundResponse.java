package com.agentgate.gateway.playground;

import com.agentgate.gateway.policy.PolicyEvaluation.RuleCheck;
import com.agentgate.gateway.policy.PolicyStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** What the policy engine would decide for the sample call, and why. */
public record PlaygroundResponse(
        @Schema(example = "ALLOW") Decision decision,
        @Schema(description = "Rule that decided, or 'default-deny'", example = "support-refund-up-to-2000") String ruleId,
        String reason,
        @Schema(description = "Policy version used (0 = no active version)") int policyVersion,
        @Schema(description = "Status of that version; null when no version is active") PolicyStatus policyStatus,
        boolean dryRun,
        @Schema(description = "Extra hint, e.g. that a dry-run version would only log a denial") String note,
        @Schema(description = "Every rule of the version, in order, and what happened to it") List<RuleCheck> checks) {

    public enum Decision { ALLOW, DENY }
}
