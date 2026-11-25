package com.agentgate.gateway.failsafe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.agentgate.gateway.audit.AuditProperties;
import com.agentgate.gateway.audit.AuditRecordRepository;
import com.agentgate.gateway.audit.AuditWriter;
import com.agentgate.gateway.guard.RedactionStep;
import com.agentgate.gateway.pipeline.CallCompleted;
import com.agentgate.gateway.shared.failsafe.PolicyStoreStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.ObjectMapper;

/** QA4: when the in-memory audit queue is full, the new record is dropped loudly (metric + error log), nothing blocks. */
class AuditQueueFullTest {

    @Test
    void fullQueueDropsNewRecordsAndCountsThem() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        TransactionOperations transactions = mock(TransactionOperations.class);
        doThrow(new CannotCreateTransactionException("database down")).when(transactions).executeWithoutResult(any());
        var writer = new AuditWriter(mock(AuditRecordRepository.class), mock(EntityManager.class), transactions,
                mock(RedactionStep.class), new ObjectMapper(),
                new PolicyStoreStatus(Clock.systemUTC(), meters),
                new AuditProperties(3, Duration.ofMillis(10)), meters);
        // the writer thread is not started here, so nothing leaves the queue: this is "database down for a while"

        for (int i = 0; i < 5; i++) {
            writer.onCallCompleted(event());
        }

        assertThat(writer.queuedCount()).isEqualTo(3);
        assertThat(meters.get("agentgate.audit.dropped").counter().count()).isEqualTo(2.0);
        assertThat(meters.get("agentgate.audit.queue.size").gauge().value()).isEqualTo(3.0);
    }

    private static CallCompleted event() {
        return new CallCompleted(UUID.randomUUID(), Instant.now(), "support-bot", null, "getCustomer", null,
                "ALLOWED", "PolicyStep", "r", 1, null, null, null, null, 1, 200, "completed");
    }
}
