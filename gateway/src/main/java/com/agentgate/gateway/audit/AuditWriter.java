package com.agentgate.gateway.audit;

import com.agentgate.gateway.guard.RedactionStep;
import com.agentgate.gateway.pipeline.CallCompleted;
import jakarta.annotation.PreDestroy;
import jakarta.persistence.EntityManager;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
 * ONE single-threaded writer, for two reasons:
 * 1. the chain cannot fork: each record needs the hash of the one before it, so records are added one at a time;
 * 2. the agent's call only puts the event in a queue and returns at once, so auditing adds no latency (QA2).
 *
 * (A second gateway instance writing to the same table would break rule 1; the primary key on seq makes that fail
 * loudly instead of forking silently. Out of scope: one instance.)
 */
@Component
public class AuditWriter {

    private static final Logger log = LoggerFactory.getLogger(AuditWriter.class);
    private static final int ATTEMPTS = 3;

    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "audit-writer");
        thread.setDaemon(true);
        return thread;
    });

    private final AuditRecordRepository repository;
    private final EntityManager entityManager;
    private final TransactionOperations transactions;
    private final RedactionStep redaction;
    private final ObjectMapper objectMapper;

    public AuditWriter(AuditRecordRepository repository, EntityManager entityManager,
                       TransactionOperations transactions, RedactionStep redaction, ObjectMapper objectMapper) {
        this.repository = repository;
        this.entityManager = entityManager;
        this.transactions = transactions;
        this.redaction = redaction;
        this.objectMapper = objectMapper;
    }

    /** fallbackExecution: the pipeline runs outside a transaction, and the event must still be handled. */
    @TransactionalEventListener(fallbackExecution = true)
    public void onCallCompleted(CallCompleted event) {
        writer.execute(() -> writeWithRetry(event));
    }

    /** Waits until everything queued so far is written. Used before verifying and in tests. */
    public void flush() {
        try {
            writer.submit(() -> { }).get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while flushing the audit queue", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("The audit queue did not drain", e);
        }
    }

    @PreDestroy
    void stop() throws InterruptedException {
        writer.shutdown();   // finish what is queued, then stop
        writer.awaitTermination(10, TimeUnit.SECONDS);
    }

    private void writeWithRetry(CallCompleted event) {
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                transactions.executeWithoutResult(status -> append(event));
                return;
            } catch (RuntimeException e) {
                log.error("Audit write failed for call {} (attempt {}/{})", event.callId(), attempt, ATTEMPTS, e);
                sleepQuietly(200L * attempt);
            }
        }
        log.error("AUDIT RECORD LOST for call {} tool={} agent={}", event.callId(), event.toolName(), event.agentId());
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
