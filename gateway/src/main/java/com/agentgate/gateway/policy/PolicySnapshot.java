package com.agentgate.gateway.policy;

import java.util.List;

/** The active policy as held in memory: version number, dry-run flag and parsed rules. Immutable. */
public record PolicySnapshot(int version, boolean dryRun, List<PolicyRule> rules) {

    /** No active version at all: no rules, so every call falls to default deny. */
    public static final PolicySnapshot NONE = new PolicySnapshot(0, false, List.of());

    public PolicySnapshot {
        rules = List.copyOf(rules);
    }
}
