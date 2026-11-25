package com.agentgate.gateway.registry;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Where the demo tool server (mock-tools) runs. Only set in the "docker" profile. */
@Validated
@ConfigurationProperties("agentgate.demo-tools")
public record DemoToolProperties(@NotBlank String baseUrl) {
}
