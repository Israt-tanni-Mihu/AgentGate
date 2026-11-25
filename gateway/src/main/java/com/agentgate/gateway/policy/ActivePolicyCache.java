package com.agentgate.gateway.policy;

import com.agentgate.gateway.shared.failsafe.PolicyStoreState;
import com.agentgate.gateway.shared.failsafe.PolicyStoreStatus;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the ACTIVE policy version in memory, so a call never waits for the database to read policies.
 *
 * FR3 "takes effect within 10 s without restart":
 * - activating a version (or toggling its dry-run) refreshes this cache immediately;
 * - a scheduled refresh every 5 s also picks up changes made elsewhere (e.g. another gateway instance).
 *
 * QA4: if the database cannot be read, the last known policy is kept and the store is marked STALE.
 * {@link PolicyStep} then decides, per risk level, whether that policy may still be used.
 * The same scheduled refresh is the "probe" that notices when the database is back.
 */
@Component
public class ActivePolicyCache {

    private static final Logger log = LoggerFactory.getLogger(ActivePolicyCache.class);

    private final PolicyVersionRepository repository;
    private final PolicyRulesJson rulesJson;
    private final PolicyStoreStatus store;

    /** volatile: a refresh on one thread is seen immediately by request threads. */
    private volatile PolicySnapshot current = PolicySnapshot.NONE;

    ActivePolicyCache(PolicyVersionRepository repository, PolicyRulesJson rulesJson, PolicyStoreStatus store) {
        this.repository = repository;
        this.rulesJson = rulesJson;
        this.store = store;
    }

    public PolicySnapshot current() {
        return current;
    }

    /** HEALTHY = the database answered at the last refresh; STALE = we run on last known policies. */
    public PolicyStoreState state() {
        return store.state();
    }

    public Instant lastSuccessfulRefresh() {
        return store.lastSuccessfulRefresh();
    }

    /** How old the policies in memory are. */
    public Duration age() {
        return store.age();
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedDelayString = "${agentgate.policy.refresh-interval:5s}",
            initialDelayString = "${agentgate.policy.refresh-interval:5s}")
    public void refresh() {
        try {
            PolicySnapshot fresh = repository.findByStatus(PolicyStatus.ACTIVE)
                    .map(rulesJson::toSnapshot)
                    .orElse(PolicySnapshot.NONE);
            PolicySnapshot previous = current;
            current = fresh;
            store.markHealthy();
            if (previous.version() != fresh.version() || previous.dryRun() != fresh.dryRun()) {
                log.info("Active policy is now version {} (dry-run: {}, {} rules)",
                        fresh.version(), fresh.dryRun(), fresh.rules().size());
            }
        } catch (RuntimeException e) {
            // Any failure means "we could not confirm the policies": keep the old ones, but say so (QA4).
            store.markUnreachable(e);
            log.warn("Could not refresh policies, keeping version {}: {}", current.version(), e.getMessage());
        }
    }
}
