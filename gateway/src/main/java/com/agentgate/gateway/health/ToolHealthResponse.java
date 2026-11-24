package com.agentgate.gateway.health;

import com.agentgate.gateway.registry.AccessMode;

/**
 * One row of GET /api/admin/tools/health.
 *
 * @param state              CLOSED (healthy), OPEN (failing, calls refused), HALF_OPEN (trial calls)
 * @param visibleToAgents    false while OPEN: the tool is hidden from agents' tool lists
 * @param failureRatePercent of the calls in the window, or null while there are too few calls to judge
 */
public record ToolHealthResponse(
        String name,
        AccessMode accessMode,
        String state,
        boolean visibleToAgents,
        Float failureRatePercent,
        int callsInWindow,
        int failedCallsInWindow,
        long refusedCalls,
        long timeoutMs) {
}
