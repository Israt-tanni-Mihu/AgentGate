package com.agentgate.gateway.identity;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * WHO is making this call — the input that later pipeline steps need:
 * policies (FR3: "may support-bot read customer X's orders?"), redaction (FR5: clearance) and audit (FR7).
 *
 * Add a {@code CallContext} parameter to any controller method under /api/agent/** and Spring fills it in
 * (see CallContextArgumentResolver).
 */
public record CallContext(
        @Schema(description = "Client id of the authenticated agent (from the JWT 'sub' claim)", example = "support-bot")
        String agentId,

        @Schema(description = "End user the agent acts for (X-On-Behalf-Of header), or null", example = "cust-1001")
        String endUserId,

        @Schema(description = "Agent clearance, used for redaction")
        Clearance clearance) {

    public boolean hasEndUser() {
        return endUserId != null;
    }
}
