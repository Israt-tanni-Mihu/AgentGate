package com.agentgate.gateway.identity;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/**
 * Runs AFTER the JWT signature and expiry have been checked. The token's "sub" claim is the agent's client id.
 *
 * We look the agent up on every call so that disabling an agent takes effect immediately,
 * not only when its token expires. Cost: one indexed lookup by client_id per call.
 */
public class AgentJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final AgentRepository agentRepository;

    public AgentJwtAuthenticationConverter(AgentRepository agentRepository) {
        this.agentRepository = agentRepository;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Agent agent = agentRepository.findByClientId(jwt.getSubject())
                .orElseThrow(() -> new InvalidBearerTokenException("Unknown agent"));
        if (!agent.isEnabled()) {
            throw new InvalidBearerTokenException("Agent is disabled");
        }
        return new AgentAuthenticationToken(jwt, agent.getClientId(), agent.getClearance());
    }
}
