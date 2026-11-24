package com.agentgate.gateway.pipeline;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * OBSERVER (see docs/patterns.md): published once for EVERY call that reached the pipeline, however it ended
 * (forwarded, denied by any step, limit, bad arguments, tool down). The audit module (FR7) listens.
 *
 * {@code arguments} are the raw arguments as the agent sent them; the audit module redacts them before storing.
 *
 * @param decision      ALLOWED (a tool answered), DENIED (a step refused) or ERROR (the tool failed)
 * @param decidedByStep the step that stopped the call, or the policy step / forward step when it went through
 * @param ruleId        the deciding rule: a policy rule id, "default-deny", "kill-switch-tool", a limit id, ...
 * @param policyVersion version of the policy that was applied, or null if the call never reached the policy
 * @param httpStatus    the status the agent got
 */
public record CallCompleted(UUID callId, Instant occurredAt, String agentId, String endUserId, String toolName,
                            JsonNode arguments, String decision, String decidedByStep, String ruleId,
                            Integer policyVersion, UUID approvalId, String approvalState, String approver,
                            JsonNode editedArguments, long latencyMs, int httpStatus, String reason) {
}
