package com.agentgate.gateway.identity;

import java.util.List;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** "This request comes from agent X" — a JWT authentication that also remembers the agent's clearance. */
public class AgentAuthenticationToken extends JwtAuthenticationToken {

    private final Clearance clearance;

    public AgentAuthenticationToken(Jwt jwt, String clientId, Clearance clearance) {
        super(jwt, List.of(new SimpleGrantedAuthority("ROLE_AGENT")), clientId);
        this.clearance = clearance;
    }

    public Clearance getClearance() {
        return clearance;
    }
}
