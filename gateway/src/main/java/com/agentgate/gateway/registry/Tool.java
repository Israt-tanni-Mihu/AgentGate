package com.agentgate.gateway.registry;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** JPA entity = one row of the "tools" table (created by Flyway migration V1). */
@Entity
@Table(name = "tools")
public class Tool {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String name;

    @Column(nullable = false, length = 2000)
    private String description;

    /** Stored as PostgreSQL jsonb. Kept as a JSON string here; the service converts it to/from JsonNode. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_schema", nullable = false)
    private String inputSchema;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", nullable = false, length = 10)
    private RiskLevel riskLevel;

    @Column(name = "owner_team", nullable = false, length = 100)
    private String ownerTeam;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ToolKind kind;

    @Column(name = "http_method", length = 10)
    private String httpMethod;

    /** FR6: READ or WRITE. Set from the HTTP method unless an admin chose otherwise. */
    @Enumerated(EnumType.STRING)
    @Column(name = "access_mode", nullable = false, length = 10)
    private AccessMode accessMode;

    @Column(name = "base_url", length = 500)
    private String baseUrl;

    @Column(length = 500)
    private String path;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
        if (accessMode == null) {
            accessMode = AccessMode.defaultFor(kind, httpMethod);
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getInputSchema() { return inputSchema; }
    public void setInputSchema(String inputSchema) { this.inputSchema = inputSchema; }

    public RiskLevel getRiskLevel() { return riskLevel; }
    public void setRiskLevel(RiskLevel riskLevel) { this.riskLevel = riskLevel; }

    public String getOwnerTeam() { return ownerTeam; }
    public void setOwnerTeam(String ownerTeam) { this.ownerTeam = ownerTeam; }

    public ToolKind getKind() { return kind; }
    public void setKind(ToolKind kind) { this.kind = kind; }

    public String getHttpMethod() { return httpMethod; }
    public void setHttpMethod(String httpMethod) { this.httpMethod = httpMethod; }

    public AccessMode getAccessMode() { return accessMode; }
    public void setAccessMode(AccessMode accessMode) { this.accessMode = accessMode; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
