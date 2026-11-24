package com.agentgate.gateway.audit;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the audit log (FR7 search + export). Filters are optional and combined with AND.
 * Built with the Criteria API so that only the filters that were sent end up in the SQL (a JPQL
 * "(:x is null or ...)" trick fails on PostgreSQL for null timestamps).
 * Results are always newest first (seq descending); the client cannot choose another sort.
 */
@Service
public class AuditSearchService {

    private static final int EXPORT_BATCH = 500;

    private final EntityManager entityManager;
    private final AuditWriter writer;

    public AuditSearchService(EntityManager entityManager, AuditWriter writer) {
        this.entityManager = entityManager;
        this.writer = writer;
    }

    @Transactional(readOnly = true)
    public Page<AuditRecordResponse> search(AuditFilter filter, Pageable pageable) {
        writer.flush();   // calls that finished a moment ago may still be in the writer's queue

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<AuditRecord> query = cb.createQuery(AuditRecord.class);
        Root<AuditRecord> root = query.from(AuditRecord.class);
        query.select(root).where(predicates(cb, root, filter, null)).orderBy(cb.desc(root.get("seq")));
        List<AuditRecordResponse> rows = entityManager.createQuery(query)
                .setFirstResult((int) pageable.getOffset()).setMaxResults(pageable.getPageSize())
                .getResultList().stream().map(AuditRecordResponse::from).toList();

        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<AuditRecord> countRoot = countQuery.from(AuditRecord.class);
        countQuery.select(cb.count(countRoot)).where(predicates(cb, countRoot, filter, null));
        long total = entityManager.createQuery(countQuery).getSingleResult();

        return new PageImpl<>(rows, pageable, total);
    }

    @Transactional(readOnly = true)
    public AuditRecordResponse get(UUID callId) {
        writer.flush();
        List<AuditRecord> found = entityManager
                .createQuery("select a from AuditRecord a where a.callId = :callId", AuditRecord.class)
                .setParameter("callId", callId).getResultList();
        if (found.isEmpty()) {
            throw new AuditNotFoundException(callId);
        }
        return AuditRecordResponse.from(found.getFirst());
    }

    /**
     * Hands every matching record to {@code sink}, oldest first, 500 at a time (keyset paging on seq), so an export
     * of a large log never holds the whole table in memory.
     */
    @Transactional(readOnly = true)
    public void export(AuditFilter filter, Consumer<AuditRecordResponse> sink) {
        writer.flush();

        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        long lastSeq = 0;
        while (true) {
            CriteriaQuery<AuditRecord> query = cb.createQuery(AuditRecord.class);
            Root<AuditRecord> root = query.from(AuditRecord.class);
            query.select(root).where(predicates(cb, root, filter, lastSeq)).orderBy(cb.asc(root.get("seq")));
            List<AuditRecord> batch = entityManager.createQuery(query).setMaxResults(EXPORT_BATCH).getResultList();
            batch.forEach(r -> sink.accept(AuditRecordResponse.from(r)));
            if (batch.size() < EXPORT_BATCH) {
                return;
            }
            lastSeq = batch.getLast().getSeq();
            entityManager.clear();   // the rows are written out already; do not keep them
        }
    }

    private static Predicate[] predicates(CriteriaBuilder cb, Root<AuditRecord> root, AuditFilter f, Long afterSeq) {
        List<Predicate> all = new ArrayList<>();
        if (hasText(f.agentId())) {
            all.add(cb.equal(root.get("agentId"), f.agentId()));
        }
        if (hasText(f.endUserId())) {
            all.add(cb.equal(root.get("endUserId"), f.endUserId()));
        }
        if (hasText(f.toolName())) {
            all.add(cb.equal(root.get("toolName"), f.toolName()));
        }
        if (f.decision() != null) {
            all.add(cb.equal(root.get("decision"), f.decision()));
        }
        if (f.from() != null) {
            all.add(cb.greaterThanOrEqualTo(root.<java.time.Instant>get("occurredAt"), f.from()));
        }
        if (f.to() != null) {
            all.add(cb.lessThanOrEqualTo(root.<java.time.Instant>get("occurredAt"), f.to()));
        }
        if (afterSeq != null) {
            all.add(cb.greaterThan(root.<Long>get("seq"), afterSeq));
        }
        return all.toArray(new Predicate[0]);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
