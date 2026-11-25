package com.agentgate.gateway.audit;

import com.agentgate.gateway.guard.RedactionStep;
import com.agentgate.gateway.pipeline.CallCompleted;
import com.agentgate.gateway.shared.failsafe.PolicyStoreStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.persistence.EntityManager;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes every {@link CallCompleted} into the hash chain (FR7).
 *
 * ONE single writer thread, for two reasons:
 * 1. the chain cannot fork: each record needs the hash of the one before it, so records are added one at a time;
 * 2. the agent's call only puts the event in a queue and returns at once, so auditing adds no latency (QA2).
 *
 * QA4: if the database is down, records wait in this bounded in-memory queue, in order. The writer takes the
 * OLDEST record, tries to save it, and only removes it from the queue after the save committed. While the database
 * is down it retries the same record, so nothing is skipped and the chain (which each time continues from the last
 * row stored in the database) stays correct when the database comes back.
 *
 * Trade-off: the queue is memory, so (a) if the gateway itself stops during an outage the queued records are lost,
 * and (b) if the queue fills up, NEW records are dropped (loud error + metric). The other choice, blocking agents'
 * calls until audit has room, would turn a database outage into a total outage, including the low-risk tools QA4
 * wants to keep alive.
 *
 * (A second gateway instance writing to the same table would break rule 1; the primary key on seq makes that fail
 * loudly instead of forking silently. Out of scope: one instance.)
 */
@Component
public class AuditWriter {

    private static final Logger log = LoggerFactory.getLogger(AuditWriter.class);
    private static final int ATTEMPTS = 3;   // for errors that are NOT "database unreachable"
    private static final long SHUTDOWN_GRACE_MS = 10_000;

    private final BlockingQueue<CallCompleted> queue;
    private final Thread worker = new Thread(this::drainLoop, "audit-writer");
    private final Counter dropped;
    private volatile boolean running = true;
    private volatile long giveUpAtNanos = Long.MAX_VALUE;

    private final AuditRecordRepository repository;
    private final EntityManager entityManager;
    private final TransactionOperations transactions;
    private final RedactionStep redaction;
    private final ObjectMapper objectMapper;
    private final PolicyStoreStatus store;
    private final AuditProperties properties;

    public AuditWriter(AuditRecordRepository repository, EntityManager entityManager,
                       TransactionOperations transactions, RedactionStep redaction, ObjectMapper objectMapper,
                       PolicyStoreStatus store, AuditProperties properties, MeterRegistry meters) {
        this.repository = repository;
        this.entityManager = entityManager;
        this.transactions = transactions;
        this.redaction = redaction;
        this.objectMapper = objectMapper;
        this.store = store;
        this.properties = properties;
        this.queue = new ArrayBlockingQueue<>(properties.queueCapacity());
        this.worker.setDaemon(true);
        Gauge.builder("agentgate.audit.queue.size", queue, BlockingQueue::size)
                .description("Audit records waiting to be written").register(meters);
        this.dropped = Counter.builder("agentgate.audit.dropped")
                .description("Audit records lost because the in-memory queue was full").register(meters);
    }

    @PostConstruct
    void start() {
        worker.start();
    }

    /** fallbackExecution: the pipeline runs outside a transaction, and the event must still be handled. */
    @TransactionalEventListener(fallbackExecution = true)
    public void onCallCompleted(CallCompleted event) {
        if (!queue.offer(event)) {
            dropped.increment();
            log.error("AUDIT QUEUE FULL ({} records waiting, database down?): record for call {} tool={} agent={} "
                            + "was NOT saved", queue.size(), event.callId(), event.toolName(), event.agentId());
        }
    }

    /** Records waiting to be written (0 when the database is healthy and the writer has caught up). */
    public int queuedCount() {
        return queue.size();
    }

    /** Waits until everything queued so far is written. Used before verifying and in tests. */
    public void flush() {
        flush(30_000);
    }

    public void flush(long timeoutMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        // A record leaves the queue only after its save committed, so "empty" means "all written".
        while (!queue.isEmpty()) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("The audit queue did not drain (" + queue.size() + " waiting)");
            }
            sleepQuietly(10);
        }
    }

    @PreDestroy
    void stop() throws InterruptedException {
        running = false;   // finish what is queued (if the database allows), then stop
        giveUpAtNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SHUTDOWN_GRACE_MS);
        worker.join(SHUTDOWN_GRACE_MS + 1_000);
        if (!queue.isEmpty()) {
            log.error("AUDIT RECORDS LOST at shutdown: {} records were still waiting for the database", queue.size());
        }
    }

    private void drainLoop() {
        boolean outageLogged = false;
        while (true) {
            CallCompleted next = queue.peek();   // peek, not take: it stays queued until it is really saved
            if (next == null) {
                if (!running) {
                    return;
                }
                sleepQuietly(20);
                continue;
            }
            if (System.nanoTime() > giveUpAtNanos) {
                return;
            }
            try {
                writeWithRetry(next);
                queue.poll();
                if (outageLogged) {
                    log.info("Audit database is back; {} records still queued", queue.size());
                    outageLogged = false;
                }
            } catch (DatabaseUnreachable e) {
                store.markUnreachable(e.getCause());
                if (!outageLogged) {
                    log.warn("Audit database unreachable; keeping records in memory ({} queued, max {})",
                            queue.size(), properties.queueCapacity());
                    outageLogged = true;
                }
                sleepQuietly(properties.retryDelay().toMillis());
            }
        }
    }

    /** Saves one record. Throws {@link DatabaseUnreachable} if the database is down (the caller retries later). */
    private void writeWithRetry(CallCompleted event) {
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                transactions.executeWithoutResult(status -> append(event));
                return;
            } catch (RuntimeException e) {
                if (PolicyStoreStatus.isStoreFailure(e)) {
                    throw new DatabaseUnreachable(e);
                }
                log.error("Audit write failed for call {} (attempt {}/{})", event.callId(), attempt, ATTEMPTS, e);
                sleepQuietly(200L * attempt);
            }
        }
        // A record the database refuses for another reason would block the queue forever; give it up loudly.
        log.error("AUDIT RECORD LOST for call {} tool={} agent={}", event.callId(), event.toolName(), event.agentId());
    }

    private static final class DatabaseUnreachable extends RuntimeException {
        DatabaseUnreachable(Throwable cause) {
            super(cause);
        }
    }

    private void append(CallCompleted event) {
        AuditRecord last = repository.findFirstByOrderBySeqDesc().orElse(null);
        long seq = last == null ? 1 : last.getSeq() + 1;
        String prevHash = last == null ? AuditHasher.GENESIS_HASH : last.getHash();

        AuditFields fields = new AuditFields(seq, event.callId(),
                event.occurredAt().truncatedTo(ChronoUnit.MICROS),   // the database keeps microseconds
                event.agentId(), event.endUserId(), event.toolName(),
                redactedText(event.arguments()), event.decision(), event.decidedByStep(), event.ruleId(),
                event.policyVersion(), event.approvalId(), event.approvalState(), event.approver(),
                event.editedArguments() == null ? null : redactedText(event.editedArguments()),
                event.latencyMs(), event.httpStatus(), event.reason());
        entityManager.persist(new AuditRecord(fields, prevHash, AuditHasher.hash(prevHash, fields)));
    }

    private String redactedText(JsonNode arguments) {
        return objectMapper.writeValueAsString(redaction.redactEverything(arguments));
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
