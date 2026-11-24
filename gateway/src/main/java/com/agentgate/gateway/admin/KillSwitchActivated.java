package com.agentgate.gateway.admin;

/**
 * Published when a kill switch goes from OFF to ON (not when it is already on).
 *
 * TODO (FR4, approvals, not built yet): the approval module must listen to this event and cancel every
 * pending approval that the switch covers (AGENT: that agent's; TOOL: that tool's; ALL_WRITES: all WRITE tools).
 */
public record KillSwitchActivated(KillSwitchScope scope, String target) {
}
