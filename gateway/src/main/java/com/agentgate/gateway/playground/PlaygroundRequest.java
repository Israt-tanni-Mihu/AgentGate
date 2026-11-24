package com.agentgate.gateway.playground;

import com.agentgate.gateway.identity.CallContextArgumentResolver;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import tools.jackson.databind.node.ObjectNode;

/** Body of POST /api/admin/policies/playground: a SAMPLE call. Nothing is forwarded, nothing is saved. */
public record PlaygroundRequest(
        @Schema(example = "support-bot")
        @NotBlank String agentId,

        @Schema(example = "issueRefund")
        @NotBlank String toolName,

        @Schema(description = "The tool arguments, exactly as an agent would send them",
                example = "{\"orderId\": 1001, \"amount\": 1500, \"reason\": \"damaged\"}")
        ObjectNode arguments,

        @Schema(description = "End user the agent acts for (like the X-On-Behalf-Of header). Optional.", example = "1")
        @Pattern(regexp = CallContextArgumentResolver.END_USER_ID_REGEX,
                message = "1-128 characters: letters, digits, '.', '_', '@', ':' or '-'")
        String endUser,

        @Schema(description = "Policy version to test, e.g. a DRAFT. Leave empty to use the ACTIVE version.")
        @Positive Integer policyVersion) {
}
