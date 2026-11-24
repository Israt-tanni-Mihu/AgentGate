package com.agentgate.gateway.approval;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequest, UUID> {

    Page<ApprovalRequest> findByState(ApprovalState state, Pageable pageable);

    List<ApprovalRequest> findByState(ApprovalState state);

    /** Row lock: an approver and the timeout cannot decide the same request at the same moment. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ApprovalRequest a where a.id = :id")
    Optional<ApprovalRequest> findForUpdate(@Param("id") UUID id);
}
