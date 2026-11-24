package com.agentgate.gateway.policy;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * One rule: "EFFECT when the agent is one of AGENTS, the tool is one of TOOLS, and ALL CONDITIONS hold".
 * "*" in agents or tools means "any".
 */
public record PolicyRule(
        @Schema(example = "support-refund-up-to-2000")
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9._-]{1,64}$", message = "letters, digits, '.', '_' or '-' (max 64)")
        String id,

        @Schema(example = "Support bot may refund at most 2000 BDT")
        @Size(max = 300)
        String description,

        @NotNull Effect effect,

        @Schema(example = "[\"support-bot\"]")
        @NotEmpty List<@NotBlank String> agents,

        @Schema(example = "[\"issueRefund\"]")
        @NotEmpty List<@NotBlank String> tools,

        @Valid List<Condition> conditions) {

    public PolicyRule {
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
    }

    public enum Effect { ALLOW, DENY }

    boolean appliesTo(String agentId, String toolName) {
        return (agents.contains("*") || agents.contains(agentId)) && (tools.contains("*") || tools.contains(toolName));
    }
}
