package com.agentgate.gateway.policy;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import tools.jackson.databind.JsonNode;

/** "argument OPERATOR value", e.g. amount lessThanOrEqual 2000. matchesEndUser needs no value. */
public record Condition(
        @Schema(example = "amount") @NotBlank String argument,
        @Schema(example = "lessThanOrEqual",
                description = "equals, notEquals, lessThanOrEqual, greaterThanOrEqual, in, matchesEndUser, hasEndUser")
        @NotBlank String operator,
        @Schema(example = "2000") JsonNode value) {
}
