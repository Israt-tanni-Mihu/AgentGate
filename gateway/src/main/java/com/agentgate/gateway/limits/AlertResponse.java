package com.agentgate.gateway.limits;

import java.time.Instant;

/** What the admin API returns for one alert. */
public record AlertResponse(Long id, AlertType alertType, String agentId, String toolName, String message,
                            Instant createdAt) {

    static AlertResponse from(Alert alert) {
        return new AlertResponse(alert.getId(), alert.getAlertType(), alert.getAgentId(), alert.getToolName(),
                alert.getMessage(), alert.getCreatedAt());
    }
}
