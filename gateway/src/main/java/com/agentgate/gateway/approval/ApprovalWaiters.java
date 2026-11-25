package com.agentgate.gateway.approval;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Connects "a decision was saved" to "the held agent call wakes up". Each held call registers a future here;
 * the decision event completes it. Completing AFTER the commit means the agent never sees a decision that was rolled back.
 *
 * In memory, per gateway instance (a held HTTP call lives on one instance anyway).
 */
@Component
class ApprovalWaiters {

    private final Map<UUID, CompletableFuture<ApprovalDecided>> waiting = new ConcurrentHashMap<>();

    ApprovalWaiters(MeterRegistry meters) {
        // C4: read from memory, so a metrics scrape never touches the database (it also works during a DB outage).
        Gauge.builder("agentgate.approvals.pending", waiting, Map::size)
                .description("Held calls waiting for a human decision on this gateway instance").register(meters);
    }

    CompletableFuture<ApprovalDecided> register(UUID id) {
        return waiting.computeIfAbsent(id, key -> new CompletableFuture<>());
    }

    void remove(UUID id) {
        waiting.remove(id);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onDecided(ApprovalDecided event) {
        CompletableFuture<ApprovalDecided> future = waiting.get(event.approvalId());
        if (future != null) {
            future.complete(event);
        }
    }
}
