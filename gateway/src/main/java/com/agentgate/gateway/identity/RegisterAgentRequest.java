package com.agentgate.gateway.identity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Body of POST /api/admin/agents. The secret is NOT in here: the gateway generates it. */
public record RegisterAgentRequest(
        @Schema(example = "refund-bot", description = "OAuth2 client id the agent will log in with")
        @NotBlank
        @Pattern(regexp = "^[a-z][a-z0-9-]{2,63}$",
                message = "must start with a lowercase letter and contain only a-z, 0-9 or '-' (3 to 64 chars)")
        String clientId,

        @Schema(example = "ShopBD refund bot")
        @NotBlank @Size(max = 100)
        String displayName,

        @NotNull Clearance clearance) {
}
