package com.agentgate.gateway.registry;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/** Body of POST and PUT /api/admin/tools. Bean Validation checks it before the controller runs. */
public record ToolRequest(
        @Schema(example = "getOrder", description = "Unique tool name agents will use")
        @NotBlank
        @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_-]{0,63}$",
                message = "must start with a letter and contain only letters, digits, '_' or '-' (max 64)")
        String name,

        @Schema(example = "Read-only. Returns an order by id.")
        @NotBlank @Size(max = 2000)
        String description,

        @Schema(description = "JSON Schema of the tool's arguments (must be a JSON object)",
                example = "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"integer\"}},\"required\":[\"id\"]}")
        @NotNull
        JsonNode inputSchema,

        @NotNull RiskLevel riskLevel,

        @Schema(example = "shop-team")
        @NotBlank @Size(max = 100)
        String ownerTeam,

        @NotNull ToolKind kind,

        @Schema(example = "GET", description = "REST tools only")
        @Pattern(regexp = "GET|POST|PUT|PATCH|DELETE")
        String httpMethod,

        @Schema(example = "http://localhost:8081", description = "REST tools only")
        @Size(max = 500) @Pattern(regexp = "^https?://\\S+$", message = "must be an http(s) URL")
        String baseUrl,

        @Schema(example = "/api/orders/{id}", description = "REST tools only")
        @Size(max = 500) @Pattern(regexp = "^/\\S*$", message = "must start with '/'")
        String path,

        @Schema(description = "Defaults to true")
        Boolean enabled,

        @Schema(description = "READ or WRITE (FR6). Omit to use the default from the HTTP method: GET = READ, others = WRITE")
        AccessMode accessMode,

        @Schema(description = "FR10: how long the gateway waits for this tool, in milliseconds (100-10000). "
                + "Omit to use the gateway default (agentgate.resilience.default-timeout)", example = "2000")
        @Min(100) @Max(10_000)
        Integer timeoutMs) {

    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "must be a JSON object")
    public boolean isInputSchemaObject() {
        return inputSchema == null || inputSchema.isObject();
    }

    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "REST tools need httpMethod, baseUrl and path; MCP tools must not set httpMethod or path")
    public boolean isTargetValidForKind() {
        if (kind == ToolKind.REST) {
            return httpMethod != null && baseUrl != null && path != null;
        }
        return httpMethod == null && path == null;
    }
}
