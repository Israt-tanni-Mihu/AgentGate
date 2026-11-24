package com.agentgate.gateway.policy;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Body of POST /api/admin/policies: a complete new rule set, saved as a DRAFT version. */
public record CreatePolicyVersionRequest(
        @Schema(example = "Allow support-bot to send SMS")
        @Size(max = 500) String description,

        @Schema(description = "Start in dry-run mode (only log what would be blocked). Defaults to false.")
        Boolean dryRun,

        @NotNull @Size(max = 500) List<@Valid @NotNull PolicyRule> rules) {
}
