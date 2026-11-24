package com.agentgate.gateway.policy;

import jakarta.validation.constraints.NotNull;

/** Body of PUT /api/admin/policies/{version}/dry-run, e.g. {"enabled": true}. */
public record DryRunRequest(@NotNull Boolean enabled) {
}
