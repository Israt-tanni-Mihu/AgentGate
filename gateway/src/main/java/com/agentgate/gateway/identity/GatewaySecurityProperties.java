package com.agentgate.gateway.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed settings under "agentgate.security" (course rule C2).
 * Outside the "local" profile, ADMIN_USERNAME and ADMIN_PASSWORD must be set, or the app refuses to start.
 */
@Validated
@ConfigurationProperties("agentgate.security")
public record GatewaySecurityProperties(
        @Valid @NotNull Admin admin,
        @NotNull Duration accessTokenTtl) {

    public record Admin(
            @NotBlank(message = "set the ADMIN_USERNAME environment variable") String username,
            @NotBlank(message = "set the ADMIN_PASSWORD environment variable") String password) {
    }
}
