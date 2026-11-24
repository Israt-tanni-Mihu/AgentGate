package com.agentgate.gateway.admin;

/** What a kill switch disables: one agent, one tool, or every WRITE tool at once. */
public enum KillSwitchScope {
    AGENT, TOOL, ALL_WRITES
}
