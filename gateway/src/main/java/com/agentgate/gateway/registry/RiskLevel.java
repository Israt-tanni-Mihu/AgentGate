package com.agentgate.gateway.registry;

/** How dangerous a tool is. Later phases use it: HIGH needs human approval (FR4), QA4 fail-safe rules. */
public enum RiskLevel {
    LOW, MEDIUM, HIGH
}
