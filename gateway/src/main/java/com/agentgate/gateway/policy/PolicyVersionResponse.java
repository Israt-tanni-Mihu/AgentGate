package com.agentgate.gateway.policy;

import java.time.Instant;
import java.util.List;

public record PolicyVersionResponse(
        int version,
        PolicyStatus status,
        boolean dryRun,
        String description,
        List<PolicyRule> rules,
        Instant createdAt,
        Instant activatedAt) {
}
