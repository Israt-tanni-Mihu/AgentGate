package com.agentgate.gateway.shared.failsafe;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

/**
 * QA4: shows in /actuator/health ("policyStore") whether the gateway runs on live or on last known policies.
 * STALE is not DOWN: the gateway still serves low-risk calls, so this must not make the whole app look dead.
 */
@Component("policyStore")
public class PolicyStoreHealthIndicator implements HealthIndicator {

    static final Status STALE = new Status("STALE");

    private final PolicyStoreStatus status;

    public PolicyStoreHealthIndicator(PolicyStoreStatus status) {
        this.status = status;
    }

    @Override
    public Health health() {
        Health.Builder builder = status.state() == PolicyStoreState.HEALTHY ? Health.up() : Health.status(STALE);
        return builder.withDetail("state", status.state())
                .withDetail("lastSuccessfulRefresh", status.lastSuccessfulRefresh().toString())
                .withDetail("ageSeconds", status.age().toSeconds())
                .build();
    }
}
