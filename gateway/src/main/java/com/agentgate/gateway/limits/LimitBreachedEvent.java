package com.agentgate.gateway.limits;

import java.time.Instant;

/**
 * Published (Observer, see docs/patterns.md) the first time a limit is breached in a window.
 * AlertService listens and stores an alert. Later features (notifications, audit) can listen too.
 */
public record LimitBreachedEvent(String limitId, String agentId, String toolName, int max, Instant occurredAt) {
}
