package com.agentgate.gateway.audit;

import java.time.Instant;

/** The optional search filters of the audit API. A null field means "do not filter on this". from/to are inclusive. */
public record AuditFilter(String agentId, String endUserId, String toolName, String decision, Instant from, Instant to) {

    public AuditFilter {
        decision = decision == null || decision.isBlank() ? null : decision.trim().toUpperCase();
        if (from != null && to != null && from.isAfter(to)) {
            throw new AuditBadRequestException("'from' must not be after 'to'");
        }
    }
}
