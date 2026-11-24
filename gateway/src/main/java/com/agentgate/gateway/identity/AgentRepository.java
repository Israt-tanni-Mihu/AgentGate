package com.agentgate.gateway.identity;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentRepository extends JpaRepository<Agent, Long> {

    Optional<Agent> findByClientId(String clientId);

    boolean existsByClientId(String clientId);
}
