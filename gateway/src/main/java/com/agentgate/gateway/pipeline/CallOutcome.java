package com.agentgate.gateway.pipeline;

import java.util.Map;
import tools.jackson.databind.JsonNode;

/** How a call ended. "sealed" = these are the only possible outcomes, so the compiler checks every switch. */
public sealed interface CallOutcome {

    /** The tool was called; this is its HTTP status and JSON result. */
    record Completed(int status, JsonNode body) implements CallOutcome {
    }

    /**
     * A step refused the call.
     *
     * @param rule    what decided it, e.g. a policy rule id or "default-deny"
     * @param reason  human-readable explanation for the agent
     * @param details extra facts for the error response (e.g. policyVersion)
     */
    record Denied(String rule, String reason, Map<String, Object> details) implements CallOutcome {
    }
}
