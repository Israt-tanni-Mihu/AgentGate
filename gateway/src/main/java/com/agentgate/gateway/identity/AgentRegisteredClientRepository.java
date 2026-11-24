package com.agentgate.gateway.identity;

import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Component;

/**
 * ADAPTER (see docs/patterns.md): Spring Authorization Server asks a {@link RegisteredClientRepository}
 * "who is client X?". We answer from OUR agents table, converting an {@link Agent} into a {@link RegisteredClient}.
 *
 * A disabled agent is reported as "not found", so the token endpoint answers 401 invalid_client.
 */
@Component
public class AgentRegisteredClientRepository implements RegisteredClientRepository {

    private final AgentRepository agentRepository;
    private final GatewaySecurityProperties properties;

    public AgentRegisteredClientRepository(AgentRepository agentRepository, GatewaySecurityProperties properties) {
        this.agentRepository = agentRepository;
        this.properties = properties;
    }

    @Override
    public RegisteredClient findByClientId(String clientId) {
        return agentRepository.findByClientId(clientId)
                .filter(Agent::isEnabled)
                .map(this::toRegisteredClient)
                .orElse(null);
    }

    @Override
    public RegisteredClient findById(String id) {
        try {
            return agentRepository.findById(Long.valueOf(id))
                    .filter(Agent::isEnabled)
                    .map(this::toRegisteredClient)
                    .orElse(null);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Agents are created through the admin API (AgentService), never by the authorization server itself. */
    @Override
    public void save(RegisteredClient registeredClient) {
        throw new UnsupportedOperationException("Register agents through /api/admin/agents");
    }

    RegisteredClient toRegisteredClient(Agent agent) {
        return RegisteredClient.withId(agent.getId().toString())
                .clientId(agent.getClientId())
                .clientSecret(agent.getClientSecretHash())   // a {bcrypt} hash; the PasswordEncoder bean checks it
                .clientName(agent.getDisplayName())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)   // machine-to-machine only
                .tokenSettings(TokenSettings.builder().accessTokenTimeToLive(properties.accessTokenTtl()).build())
                .build();
    }
}
