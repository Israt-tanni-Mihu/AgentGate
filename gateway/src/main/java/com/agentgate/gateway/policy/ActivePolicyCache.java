package com.agentgate.gateway.policy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the ACTIVE policy version in memory, so a call never waits for the database to read policies.
 *
 * FR3 "takes effect within 10 s without restart":
 * - activating a version (or toggling its dry-run) refreshes this cache immediately;
 * - a scheduled refresh every 5 s also picks up changes made elsewhere (e.g. another gateway instance).
 * If the database cannot be read, the last known policy is kept (the start of QA4 fail-safe).
 */
@Component
public class ActivePolicyCache {

    private static final Logger log = LoggerFactory.getLogger(ActivePolicyCache.class);

    private final PolicyVersionRepository repository;
    private final PolicyRulesJson rulesJson;

    /** volatile: a refresh on one thread is seen immediately by request threads. */
    private volatile PolicySnapshot current = PolicySnapshot.NONE;

    ActivePolicyCache(PolicyVersionRepository repository, PolicyRulesJson rulesJson) {
        this.repository = repository;
        this.rulesJson = rulesJson;
    }

    public PolicySnapshot current() {
        return current;
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
            if (previous.version() != fresh.version() || previous.dryRun() != fresh.dryRun()) {
                log.info("Active policy is now version {} (dry-run: {}, {} rules)",
                        fresh.version(), fresh.dryRun(), fresh.rules().size());
            }
        } catch (DataAccessException e) {
            log.warn("Could not refresh policies, keeping version {}: {}", current.version(), e.getMessage());
        }
    }
}
