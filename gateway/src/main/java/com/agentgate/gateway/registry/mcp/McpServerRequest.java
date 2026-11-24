package com.agentgate.gateway.registry.mcp;

import com.agentgate.gateway.registry.RiskLevel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Body of POST /api/admin/mcp-servers. */
public record McpServerRequest(
        @Schema(example = "shop-parcels")
        @NotBlank @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_-]{0,63}$",
                message = "must start with a letter and contain only letters, digits, '_' or '-' (max 64)")
        String name,

        @Schema(example = "http://localhost:8081/mcp", description = "The server's Streamable HTTP MCP endpoint")
        @NotBlank @Size(max = 500) @Pattern(regexp = "^https?://\\S+$", message = "must be an http(s) URL")
        String url,

        @Schema(example = "shop-team")
        @NotBlank @Size(max = 100)
        String ownerTeam,

        @Schema(description = "Risk level of newly imported tools. Change one tool later with PUT /api/admin/tools/{id}")
        @NotNull
        RiskLevel defaultRiskLevel) {
}
