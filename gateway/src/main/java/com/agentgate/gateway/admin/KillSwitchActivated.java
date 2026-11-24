package com.agentgate.gateway.admin;

/**
 * Published when a kill switch goes from OFF to ON (not when it is already on).
 *
 * The approval module listens (ApprovalKillSwitchListener) and cancels every pending approval that the switch
 * covers (AGENT: that agent's; TOOL: that tool's; ALL_WRITES: all WRITE tools).
 */
public record KillSwitchActivated(KillSwitchScope scope, String target) {
}
