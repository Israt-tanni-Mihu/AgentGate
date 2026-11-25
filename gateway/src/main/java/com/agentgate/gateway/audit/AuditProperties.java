package com.agentgate.gateway.audit;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * @param queueCapacity how many audit records may wait in memory while the database is down (QA4)
 * @param retryDelay    pause between write attempts while the database is down
 */
@Validated
@ConfigurationProperties("agentgate.audit")
public record AuditProperties(
        @DefaultValue("10000") @Min(1) int queueCapacity,
        @DefaultValue("1s") @NotNull Duration retryDelay) {

    @Configuration
    @EnableConfigurationProperties(AuditProperties.class)
    static class Registration {
    }
}
