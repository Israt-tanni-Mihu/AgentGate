package com.agentgate.gateway.limits;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** One row of the "alerts" table (Flyway V4): something an admin should look at. */
@Entity
@Table(name = "alerts")
public class Alert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "alert_type", nullable = false, length = 40)
    private AlertType alertType;

    @Column(name = "agent_id", length = 64)
    private String agentId;

    @Column(name = "tool_name", length = 64)
    private String toolName;

    @Column(nullable = false, length = 500)
    private String message;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Alert() {
        // for JPA
    }

    public Alert(AlertType alertType, String agentId, String toolName, String message, Instant createdAt) {
        this.alertType = alertType;
        this.agentId = agentId;
        this.toolName = toolName;
        this.message = message;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public AlertType getAlertType() { return alertType; }
    public String getAgentId() { return agentId; }
    public String getToolName() { return toolName; }
    public String getMessage() { return message; }
    public Instant getCreatedAt() { return createdAt; }
}
