package com.agentgate.gateway.identity;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Secrets of the two demo agents. Used in the "local" profile (defaults in application.yml) and the "docker" profile (from env vars, no defaults). */
@Validated
@ConfigurationProperties("agentgate.demo-agents")
public record DemoAgentProperties(
        @NotBlank String supportBotSecret,
        @NotBlank String analyticsBotSecret) {
}
