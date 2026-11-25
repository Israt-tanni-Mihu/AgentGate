package com.agentgate.gateway.shared.failsafe;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * QA4: the one place that knows whether the policy store (PostgreSQL) is reachable.
 *
 * - {@link ActivePolicyCache} calls {@link #markHealthy()} after each successful refresh, so "last success" means
 *   "the last time the store really answered".
 * - Anything on the call path that touches the database reports a connection failure with {@link #markUnreachable},
 *   so the state flips at once and does not wait for the next scheduled refresh.
 * - {@link #readOrFallback} is how the call path reads data: from the database while it is healthy, from the
 *   last known copy in memory while it is not (the database is not even tried, so calls do not wait for timeouts).
 */
@Component
public class PolicyStoreStatus {

    private static final Logger log = LoggerFactory.getLogger(PolicyStoreStatus.class);

    private final Clock clock;
    private volatile PolicyStoreState state = PolicyStoreState.HEALTHY;
    /** The gateway cannot start without the database (Flyway), so "now" is a true last success at startup. */
    private volatile Instant lastSuccess;

    public PolicyStoreStatus(Clock clock, MeterRegistry meters) {
        this.clock = clock;
        this.lastSuccess = clock.instant();
        Gauge.builder("agentgate.policy.store.stale", this, s -> s.state == PolicyStoreState.STALE ? 1 : 0)
                .description("1 when the policy store is unreachable and last known data is used, else 0")
                .register(meters);
        Gauge.builder("agentgate.policy.store.age.seconds", this, s -> s.age().toMillis() / 1000.0)
                .description("Seconds since the policy store last answered")
                .register(meters);
    }

    public PolicyStoreState state() {
        return state;
    }

    public Instant lastSuccessfulRefresh() {
        return lastSuccess;
    }

    /** How old the last known data is. */
    public Duration age() {
        Duration age = Duration.between(lastSuccess, clock.instant());
        return age.isNegative() ? Duration.ZERO : age;
    }

    public synchronized void markHealthy() {
        lastSuccess = clock.instant();
        if (state != PolicyStoreState.HEALTHY) {
            state = PolicyStoreState.HEALTHY;
            log.info("Policy store is reachable again; back to normal");
        }
    }

    public synchronized void markUnreachable(Throwable cause) {
        if (state != PolicyStoreState.STALE) {
            state = PolicyStoreState.STALE;
            log.warn("Policy store is UNREACHABLE; using last known data (last success {}): {}",
                    lastSuccess, cause.getMessage());
        }
    }

    /**
     * Runs {@code fromDatabase} while the store is healthy. If the store is stale, or the database turns out to be
     * unreachable, answers from {@code fromMemory}. Errors that are not about connectivity are thrown as usual.
     */
    public <T> T readOrFallback(Supplier<T> fromDatabase, Supplier<T> fromMemory) {
        if (state == PolicyStoreState.STALE) {
            return fromMemory.get();
        }
        try {
            return fromDatabase.get();
        } catch (RuntimeException e) {
            if (!isStoreFailure(e)) {
                throw e;
            }
            markUnreachable(e);
            return fromMemory.get();
        }
    }

    /** True when the exception means "the database cannot be reached" (not "the query was wrong"). */
    public static boolean isStoreFailure(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof DataAccessResourceFailureException || t instanceof CannotCreateTransactionException
                    || t instanceof QueryTimeoutException || t instanceof java.sql.SQLTransientException
                    || t instanceof java.sql.SQLNonTransientConnectionException
                    || (t instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().startsWith("08"))) {
                return true;
            }
        }
        return false;
    }
}
