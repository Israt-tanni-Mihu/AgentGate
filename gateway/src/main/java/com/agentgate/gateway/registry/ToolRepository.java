package com.agentgate.gateway.registry;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data generates the SQL for these methods from their names at startup. */
public interface ToolRepository extends JpaRepository<Tool, Long> {

    Optional<Tool> findByName(String name);

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long id);
}
