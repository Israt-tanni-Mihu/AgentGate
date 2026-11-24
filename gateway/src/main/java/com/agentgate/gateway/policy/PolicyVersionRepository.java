package com.agentgate.gateway.policy;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PolicyVersionRepository extends JpaRepository<PolicyVersion, Long> {

    Optional<PolicyVersion> findByVersion(int version);

    Optional<PolicyVersion> findByStatus(PolicyStatus status);

    @Query("select coalesce(max(p.version), 0) from PolicyVersion p")
    int findHighestVersion();
}
