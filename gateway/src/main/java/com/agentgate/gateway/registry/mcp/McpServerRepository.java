package com.agentgate.gateway.registry.mcp;

import org.springframework.data.jpa.repository.JpaRepository;

public interface McpServerRepository extends JpaRepository<McpServer, Long> {

    boolean existsByName(String name);
}
