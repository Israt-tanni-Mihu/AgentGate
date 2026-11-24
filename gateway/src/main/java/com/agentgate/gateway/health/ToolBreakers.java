package com.agentgate.gateway.health;

import com.agentgate.gateway.pipeline.ToolCallFailedException;
import com.agentgate.gateway.registry.ToolResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/**
 * FR10: one circuit breaker and one time limiter PER TOOL, created the first time the tool is called
 * (name = tool name), so a new tool from the registry is protected with no extra code.
 *
 * Breakers live in memory of this instance (like kill switches and rate counters).
 */
@Component
public class ToolBreakers {

    private final ResilienceProperties properties;
    private final CircuitBreakerRegistry breakers;
    private final ConcurrentMap<String, TimeLimiter> limiters = new ConcurrentHashMap<>();

    public ToolBreakers(ResilienceProperties properties, MeterRegistry meterRegistry) {
        this.properties = properties;
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(properties.slidingWindowSize())
                .minimumNumberOfCalls(properties.minimumCalls())
                .failureRateThreshold(properties.failureRateThreshold())
                .waitDurationInOpenState(properties.waitInOpenState())
                // OPEN -> HALF_OPEN after the wait even when no call arrives. A hidden tool gets no calls,
                // so without this it could never come back.
                .enableAutomaticTransitionFromOpenToHalfOpen()
                .permittedNumberOfCallsInHalfOpenState(properties.halfOpenTrialCalls())
                // Only "the tool is broken or slow" counts. Bad arguments (our caller's mistake) and 4xx answers do not.
                .recordException(e -> e instanceof ToolCallFailedException || e instanceof TimeoutException)
                .build();
        this.breakers = CircuitBreakerRegistry.of(config);
        // Gauge resilience4j_circuitbreaker_state{name="<tool>", state=...} appears with each new breaker.
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(breakers).bindTo(meterRegistry);
    }

    public CircuitBreaker breakerFor(String toolName) {
        return breakers.circuitBreaker(toolName);
    }

    /** The tool's own timeout if the registry has one, else the default. Read per call, so a change applies at once. */
    public Duration timeoutOf(ToolResponse tool) {
        return tool.timeoutMs() != null ? Duration.ofMillis(tool.timeoutMs()) : properties.defaultTimeout();
    }

    /** One limiter per tool; replaced when the tool's timeout was changed by an admin (no restart needed). */
    public TimeLimiter limiterFor(ToolResponse tool) {
        Duration timeout = timeoutOf(tool);
        return limiters.compute(tool.name(), (name, current) ->
                current != null && current.getTimeLimiterConfig().getTimeoutDuration().equals(timeout)
                        ? current
                        : TimeLimiter.of(name, TimeLimiterConfig.custom()
                                .timeoutDuration(timeout).cancelRunningFuture(true).build()));
    }

    /** Empty when the tool has never been called (then there is nothing to report: treat as CLOSED). */
    public Optional<CircuitBreaker> find(String toolName) {
        return breakers.find(toolName);
    }

    public boolean isOpen(String toolName) {
        return find(toolName).map(b -> b.getState() == CircuitBreaker.State.OPEN
                || b.getState() == CircuitBreaker.State.FORCED_OPEN).orElse(false);
    }

    public long retryAfterSeconds() {
        return Math.max(1, properties.waitInOpenState().toSeconds());
    }

    /** For tests: back to CLOSED with empty counters. */
    public void reset() {
        breakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
    }
}
