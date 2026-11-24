package com.agentgate.gateway.audit;

import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * Read-only on purpose: the base type {@link Repository} has no methods, so only these two exist.
 * Writing goes through {@link AuditWriter} (EntityManager.persist). There is no update and no delete.
 */
public interface AuditRecordRepository extends Repository<AuditRecord, Long> {

    Optional<AuditRecord> findFirstByOrderBySeqDesc();

    long count();
}
