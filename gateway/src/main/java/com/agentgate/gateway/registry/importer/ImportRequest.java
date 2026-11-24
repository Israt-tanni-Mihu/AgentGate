package com.agentgate.gateway.registry.importer;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Body of POST /api/admin/tools/import. */
public record ImportRequest(
        @Schema(example = "http://localhost:8081/v3/api-docs", description = "Where to download the spec from")
        @NotBlank @Size(max = 500) @Pattern(regexp = "^https?://\\S+$", message = "must be an http(s) URL")
        String specUrl,

        @Schema(example = "shop-team", description = "Team that owns all imported tools")
        @NotBlank @Size(max = 100)
        String ownerTeam,

        @Schema(example = "http://localhost:8081", description = "Where the gateway will send the tool calls")
        @NotBlank @Size(max = 500) @Pattern(regexp = "^https?://\\S+$", message = "must be an http(s) URL")
        String baseUrl) {
}
