package com.agentgate.gateway.registry.mcp;

import com.agentgate.gateway.registry.RiskLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/** One row of "mcp_servers" (Flyway V8): a downstream MCP server the admin registered. */
@Entity
@Table(name = "mcp_servers")
public class McpServer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String name;

    @Column(nullable = false, length = 500)
    private String url;

    @Column(name = "owner_team", nullable = false, length = 100)
    private String ownerTeam;

    @Enumerated(EnumType.STRING)
    @Column(name = "default_risk_level", nullable = false, length = 10)
    private RiskLevel defaultRiskLevel;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getOwnerTeam() { return ownerTeam; }
    public void setOwnerTeam(String ownerTeam) { this.ownerTeam = ownerTeam; }
    public RiskLevel getDefaultRiskLevel() { return defaultRiskLevel; }
    public void setDefaultRiskLevel(RiskLevel defaultRiskLevel) { this.defaultRiskLevel = defaultRiskLevel; }
    public Instant getCreatedAt() { return createdAt; }
}
