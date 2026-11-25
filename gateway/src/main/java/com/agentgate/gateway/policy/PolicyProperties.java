package com.agentgate.gateway.policy;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Typed settings for FR3 / QA4.
 *
 * @param refreshInterval how often the active policy is re-read from the database (also how fast an outage is noticed)
 * @param maxStale        QA4: how long LOW-risk calls may keep using the last known policies while the store is down
 */
@Validated
@ConfigurationProperties("agentgate.policy")
public record PolicyProperties(
        @DefaultValue("5s") @NotNull Duration refreshInterval,
        @DefaultValue("5m") @NotNull Duration maxStale) {

    public PolicyProperties {
        if (refreshInterval.isNegative() || refreshInterval.isZero()) {
            throw new IllegalArgumentException("agentgate.policy.refresh-interval must be positive");
        }
        if (maxStale.isNegative()) {
            throw new IllegalArgumentException("agentgate.policy.max-stale must not be negative");
        }
    }
}
