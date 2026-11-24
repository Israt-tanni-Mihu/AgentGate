package com.agentgate.gateway.admin;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KillSwitchRepository extends JpaRepository<KillSwitch, Long> {

    Optional<KillSwitch> findByScopeAndTarget(KillSwitchScope scope, String target);

    List<KillSwitch> findByEngagedTrue();
}
