package com.agentgate.gateway.policy;

public enum PolicyStatus {
    /** Being prepared; has no effect on calls. */
    DRAFT,
    /** The one version that decides calls right now. */
    ACTIVE,
    /** Was active before; kept for history and can be re-activated (rollback). */
    RETIRED
}
