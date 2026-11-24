package com.agentgate.gateway.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.test.util.ReflectionTestUtils;

/** Unit test of the Adapter: Agent row → Spring Authorization Server RegisteredClient. */
class AgentRegisteredClientRepositoryTest {

    AgentRepository agents = mock(AgentRepository.class);
    AgentRegisteredClientRepository adapter = new AgentRegisteredClientRepository(agents,
            new GatewaySecurityProperties(new GatewaySecurityProperties.Admin("admin", "pw"), Duration.ofMinutes(5)));

    @Test
    void enabledAgentBecomesClientCredentialsClient() {
        given(agents.findByClientId("support-bot")).willReturn(Optional.of(agent("support-bot", true)));

        RegisteredClient client = adapter.findByClientId("support-bot");

        assertThat(client).isNotNull();
        assertThat(client.getId()).isEqualTo("7");
        assertThat(client.getClientSecret()).isEqualTo("{bcrypt}hash");
        assertThat(client.getAuthorizationGrantTypes()).containsExactly(AuthorizationGrantType.CLIENT_CREDENTIALS);
        assertThat(client.getTokenSettings().getAccessTokenTimeToLive()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void disabledOrUnknownAgentIsNotFound() {
        given(agents.findByClientId("old-bot")).willReturn(Optional.of(agent("old-bot", false)));
        given(agents.findByClientId("ghost")).willReturn(Optional.empty());

        assertThat(adapter.findByClientId("old-bot")).isNull();
        assertThat(adapter.findByClientId("ghost")).isNull();
        assertThat(adapter.findById("not-a-number")).isNull();
    }

    private static Agent agent(String clientId, boolean enabled) {
        Agent agent = new Agent();
        ReflectionTestUtils.setField(agent, "id", 7L);
        agent.setClientId(clientId);
        agent.setClientSecretHash("{bcrypt}hash");
        agent.setDisplayName("Test bot");
        agent.setClearance(Clearance.LOW);
        agent.setEnabled(enabled);
        return agent;
    }
}
