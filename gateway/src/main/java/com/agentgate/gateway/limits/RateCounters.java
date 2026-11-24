package com.agentgate.gateway.limits;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * FR6 counters: a FIXED WINDOW counter per (limit, subject). Each window (e.g. one minute) starts at count 0.
 *
 * Simple and fast, and it needs no library. The counts live in THIS JVM's memory only: with two gateway
 * instances each instance counts on its own, so a limit can be exceeded up to N times. See the ADR candidate
 * in docs/adr/.
 */
@Component
public class RateCounters {

    /** @param count this attempt's number in the window (1 = first call); @param resetsAt when the window ends */
    public record Attempt(int count, Instant resetsAt) {
    }

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    /** Counts one attempt, even when it is over the limit, so retrying a blocked tool gives no free calls. */
    public Attempt countAttempt(String key, Instant now, Duration window) {
        long length = window.toMillis();
        long index = now.toEpochMilli() / length;   // which window we are in (counted from 1970)
        Window current = windows.compute(key, (k, old) ->
                old == null || old.index() < index ? new Window(index, new AtomicInteger()) : old);
        return new Attempt(current.count().incrementAndGet(), Instant.ofEpochMilli((index + 1) * length));
    }

    /** Forgets all counts. Tests use it so that one test does not use up the quota of another. */
    public void reset() {
        windows.clear();
    }

    private record Window(long index, AtomicInteger count) {
    }
}
