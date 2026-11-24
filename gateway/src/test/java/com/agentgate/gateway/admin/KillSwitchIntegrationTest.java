package com.agentgate.gateway.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentgate.gateway.AgentCallIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/** FR8 through the real HTTP pipeline: switches take effect at once and are released again. */
@RecordApplicationEvents
class KillSwitchIntegrationTest extends AgentCallIntegrationTest {

    private static final String REFUND = "{\"orderId\": 1001, \"amount\": 100, \"reason\": \"damaged\"}";

    @Autowired ApplicationEvents events;

    @Test
    void aKilledToolIsBlockedAtOnceAndWorksAgainWhenReleased() {
        assertThat(call("support-bot", null, "getOrder", "{\"id\": \"1001\"}")).hasStatusOk();

        assertThat(setKillSwitch("/api/admin/kill-switch/tools/getOrder", true)).hasStatusOk()
                .bodyJson().hasPathSatisfying("$.engaged", v -> v.assertThat().isEqualTo(true));
        assertThat(call("support-bot", null, "getOrder", "{\"id\": \"1001\"}")).hasStatus(403).bodyJson()
                .hasPathSatisfying("$.rule", v -> v.assertThat().isEqualTo("kill-switch-tool"));
        assertThat(received).hasSize(1);   // only the call before the switch reached the shop

        assertThat(mvc.get().uri("/api/admin/kill-switch").with(admin())).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.content[0].target", v -> v.assertThat().isEqualTo("getOrder"))
                .hasPathSatisfying("$.content[0].engaged", v -> v.assertThat().isEqualTo(true));

        assertThat(setKillSwitch("/api/admin/kill-switch/tools/getOrder", false)).hasStatusOk();
        assertThat(call("support-bot", null, "getOrder", "{\"id\": \"1001\"}")).hasStatusOk();
    }

    @Test
    void aKilledAgentIsBlockedButOtherAgentsAreNot() {
        assertThat(setKillSwitch("/api/admin/kill-switch/agents/support-bot", true)).hasStatusOk();

        assertThat(call("support-bot", null, "getOrder", "{\"id\": \"1001\"}")).hasStatus(403).bodyJson()
                .hasPathSatisfying("$.rule", v -> v.assertThat().isEqualTo("kill-switch-agent"));
        assertThat(call("analytics-bot", null, "getOrder", "{\"id\": \"1001\"}")).hasStatusOk();
    }

    @Test
    void allWritesOffBlocksIssueRefundButNotGetOrder() {
        assertThat(setKillSwitch("/api/admin/kill-switch/all-writes", true)).hasStatusOk();

        assertThat(call("support-bot", "1", "issueRefund", REFUND)).hasStatus(403).bodyJson()
                .hasPathSatisfying("$.rule", v -> v.assertThat().isEqualTo("kill-switch-all-writes"));
        assertThat(call("support-bot", null, "getOrder", "{\"id\": \"1001\"}")).hasStatusOk();
        assertThat(received).singleElement().satisfies(r -> assertThat(r.rawPath()).isEqualTo("/api/orders/1001"));

        assertThat(setKillSwitch("/api/admin/kill-switch/all-writes", false)).hasStatusOk();
        assertThat(call("support-bot", "1", "issueRefund", REFUND)).hasStatus(201);
    }

    @Test
    void killedCallsDoNotUseUpTheLimitsBecauseTheKillSwitchRunsFirst() {
        setKillSwitch("/api/admin/kill-switch/all-writes", true);
        for (int i = 0; i < 25; i++) {
            // Would be 429 on the 21st if the limits ran first. The kill switch answers 403 every time.
            assertThat(call("support-bot", "1", "issueRefund", REFUND)).hasStatus(403);
        }
        setKillSwitch("/api/admin/kill-switch/all-writes", false);

        for (int i = 0; i < 20; i++) {
            assertThat(call("support-bot", "1", "issueRefund", REFUND)).hasStatus(201);
        }
        assertThat(alertRepository.count()).isZero();
    }

    @Test
    void theEventIsPublishedOnlyWhenASwitchIsTurnedOn() {
        setKillSwitch("/api/admin/kill-switch/tools/getOrder", true);
        setKillSwitch("/api/admin/kill-switch/tools/getOrder", true);   // already on: no new event
        setKillSwitch("/api/admin/kill-switch/tools/getOrder", false);

        assertThat(events.stream(KillSwitchActivated.class).toList())
                .containsExactly(new KillSwitchActivated(KillSwitchScope.TOOL, "getOrder"));
    }

    @Test
    void aSwitchForAnUnknownAgentOrToolIsRejectedNotSilentlyIgnored() {
        assertThat(setKillSwitch("/api/admin/kill-switch/agents/ghost-bot", true)).hasStatus(404).bodyJson()
                .hasPathSatisfying("$.title", v -> v.assertThat().isEqualTo("Kill switch target not found"));
        assertThat(setKillSwitch("/api/admin/kill-switch/tools/launchRocket", true)).hasStatus(404);
    }

    @Test
    void killSwitchEndpointsRequireAdminCredentials() {
        assertThat(mvc.get().uri("/api/admin/kill-switch")).hasStatus(401);
    }
}
