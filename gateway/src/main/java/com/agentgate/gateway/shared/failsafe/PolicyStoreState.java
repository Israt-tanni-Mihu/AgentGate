package com.agentgate.gateway.shared.failsafe;

/** QA4: can the gateway reach the database (the "policy store") right now? STALE = no, we run on last known data. */
public enum PolicyStoreState {
    HEALTHY, STALE
}
