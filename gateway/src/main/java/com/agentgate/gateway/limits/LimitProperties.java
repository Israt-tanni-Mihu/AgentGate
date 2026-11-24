package com.agentgate.gateway.limits;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * FR6 limits under "agentgate.limits" (defaults in application.yml). Startup fails if a value is below 1.
 *
 * @param agentPerMinute        calls per minute, per agent (rate limit)
 * @param toolPerMinute         calls per minute, per tool (rate limit)
 * @param agentDailyQuota       calls per UTC day, per agent
 * @param toolDailyQuota        calls per UTC day, per tool
 * @param writesPerHourPerAgent WRITE calls per hour, per agent (blast-radius cap)
 */
@Validated
@ConfigurationProperties("agentgate.limits")
public record LimitProperties(
        @Min(1) int agentPerMinute,
        @Min(1) int toolPerMinute,
        @Min(1) int agentDailyQuota,
        @Min(1) int toolDailyQuota,
        @Min(1) int writesPerHourPerAgent) {
}
