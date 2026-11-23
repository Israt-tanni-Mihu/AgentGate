package com.agentgate.mocktools.chaos;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * How badly the fake tools should misbehave.
 *
 * @param delayMs  extra wait added to every /api/** call (simulates a slow tool, for timeouts)
 * @param failRate chance 0.0-1.0 that a call fails with 503 (simulates a broken tool, for circuit breakers)
 */
public record ChaosSettings(
        @Min(0) @Max(60_000) long delayMs,
        @DecimalMin("0.0") @DecimalMax("1.0") double failRate) {

    public static final ChaosSettings NONE = new ChaosSettings(0, 0.0);
}
