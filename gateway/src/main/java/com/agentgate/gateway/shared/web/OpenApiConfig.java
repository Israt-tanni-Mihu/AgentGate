package com.agentgate.gateway.shared.web;

import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.OAuthFlow;
import io.swagger.v3.oas.annotations.security.OAuthFlows;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/**
 * Adds the two login methods to Swagger UI's "Authorize" button:
 * - adminBasic: admin username/password for /api/admin/**
 * - agentToken: an agent's client id + secret; Swagger UI fetches the JWT from /oauth2/token for you.
 */
@Configuration
@SecurityScheme(name = "adminBasic", type = SecuritySchemeType.HTTP, scheme = "basic",
        description = "Admin login (ADMIN_USERNAME / ADMIN_PASSWORD)")
@SecurityScheme(name = "agentToken", type = SecuritySchemeType.OAUTH2,
        description = "Agent login with OAuth2 client credentials",
        flows = @OAuthFlows(clientCredentials = @OAuthFlow(tokenUrl = "/oauth2/token")))
public class OpenApiConfig {
}
