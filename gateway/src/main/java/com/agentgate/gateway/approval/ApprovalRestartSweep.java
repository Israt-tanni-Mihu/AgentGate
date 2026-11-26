package com.agentgate.gateway.approval;

import com.agentgate.gateway.pipeline.CallCompleted;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * FR4 fix (ADR-0001): a request is normally closed by the agent call that waits for it ({@link ApprovalStep}).
 * After a gateway restart that call is gone, so its PENDING row would stay PENDING forever.
 * On startup this sweep CANCELS every such orphan through the normal State transition ({@link ApprovalService#cancel}),
 * so the same rules, event, webhook and audit record apply as for any other cancel.
 *
 * A request with a live waiter on this instance (a call that arrived after startup) is skipped.
 * Assumes ONE gateway instance, like the rest of the approval flow.
 */
@Component
class ApprovalRestartSweep {

    static final String REASON = "gateway restarted: the agent's held call was lost";
    static final String RULE = "approval-cancelled";
    /** No HTTP answer reached the agent: its connection ended with the old gateway process. */
    static final int NO_HTTP_ANSWER = 0;

    private static final Logger log = LoggerFactory.getLogger(ApprovalRestartSweep.class);

    private final ApprovalService service;
    private final ApprovalWaiters waiters;
    private final ApplicationEventPublisher events;

    ApprovalRestartSweep(ApprovalService service, ApprovalWaiters waiters, ApplicationEventPublisher events) {
        this.service = service;
        this.waiters = waiters;
        this.events = events;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onStartup() {
        try {
            int swept = sweep();
            log.info("Approval restart sweep: {} PENDING approval(s) from a previous run cancelled", swept);
        } catch (RuntimeException e) {
            // A failed sweep must not stop the gateway; the rows stay PENDING until the next start.
            log.error("Approval restart sweep failed", e);
        }
    }

    /** Returns how many requests were cancelled. */
    int sweep() {
        int swept = 0;
        for (UUID id : service.pendingIds()) {
            if (waiters.isWaiting(id)) {
                continue;   // a live call on this instance still waits for it
            }
            try {
                ApprovalResponse cancelled = service.cancel(id, REASON);
                events.publishEvent(auditEventFor(cancelled));
                swept++;
            } catch (IllegalApprovalTransitionException alreadyDecided) {
                // decided between the query and now: nothing to do
            }
        }
        return swept;
    }

    /**
     * The audit record the lost call never wrote (its pipeline died with the old process).
     * Same shape as a cancelled held call; a new call id, because the original one was never stored.
     */
    private static CallCompleted auditEventFor(ApprovalResponse r) {
        long heldMs = Duration.between(r.createdAt(), r.decidedAt()).toMillis();
        return new CallCompleted(UUID.randomUUID(), r.decidedAt(), r.agentId(), r.endUserId(), r.toolName(),
                r.originalArguments(), "DENIED", ApprovalRestartSweep.class.getSimpleName(), RULE, null,
                r.id(), r.state().name(), null, null, heldMs, NO_HTTP_ANSWER, r.reason());
    }
}
