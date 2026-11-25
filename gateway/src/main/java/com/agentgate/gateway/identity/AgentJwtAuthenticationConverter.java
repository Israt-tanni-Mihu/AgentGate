package com.agentgate.gateway.identity;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/**
 * Runs AFTER the JWT signature and expiry have been checked (in memory, no database). The token's "sub" claim is
 * the agent's client id.
 *
 * We look the agent up on every call so that disabling an agent takes effect immediately, not only when its token
 * expires. {@link AgentDirectory} does that lookup in the database while it is healthy and from memory when it is
 * not (QA4), so a database outage does not stop agents that already hold a token.
 */
public class AgentJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final AgentDirectory agents;

    public AgentJwtAuthenticationConverter(AgentDirectory agents) {
        this.agents = agents;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        AgentDirectory.AgentIdentity agent = agents.findByClientId(jwt.getSubject())
                .orElseThrow(() -> new InvalidBearerTokenException("Unknown agent"));
        if (!agent.enabled()) {
            throw new InvalidBearerTokenException("Agent is disabled");
        }
        return new AgentAuthenticationToken(jwt, agent.clientId(), agent.clearance());
    }
}
