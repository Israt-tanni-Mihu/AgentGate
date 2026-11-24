package com.agentgate.gateway.health;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * FR10 settings under "agentgate.resilience" (defaults and the reasons for them are in application.yml).
 *
 * @param defaultTimeout       wait time for a tool that has no timeout of its own
 * @param slidingWindowSize    the breaker looks at the last N calls
 * @param minimumCalls         the breaker does not judge before it has seen this many calls
 * @param failureRateThreshold percent of failed calls (in the window) that opens the breaker
 * @param waitInOpenState      how long a breaker stays OPEN before it moves to HALF_OPEN by itself
 * @param halfOpenTrialCalls   how many trial calls HALF_OPEN lets through to decide CLOSED or OPEN again
 */
@Validated
@ConfigurationProperties("agentgate.resilience")
public record ResilienceProperties(
        @NotNull Duration defaultTimeout,
        @Min(2) int slidingWindowSize,
        @Min(1) int minimumCalls,
        @Min(1) @Max(100) int failureRateThreshold,
        @NotNull Duration waitInOpenState,
        @Min(1) int halfOpenTrialCalls) {
}
