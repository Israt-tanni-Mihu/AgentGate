package com.agentgate.gateway.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.agentgate.gateway.AgentCallIntegrationTest;
import com.agentgate.gateway.registry.Tool;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;

/**
 * FR10 through the real pipeline against a fake shop that can be slow or broken.
 * Test settings (see AgentCallIntegrationTest): default timeout 3 s, breaker open for 1 s; it opens after
 * 5 calls with >= 50% failures; 3 trial calls in HALF_OPEN. Slow-tool tests give the tool a 300 ms timeout.
 */
class ToolHealthIntegrationTest extends AgentCallIntegrationTest {

    private static final String ORDER = "{\"id\": \"1001\"}";
    private static final String REFUND = "{\"orderId\": 1001, \"amount\": 100, \"reason\": \"damaged\"}";

    @Test
    void aSlowToolAnswers504ProblemDetails() {
        shopDelayMs = 2000;
        setToolTimeout("getOrder", 300);

        assertThat(call("support-bot", null, "getOrder", ORDER)).hasStatus(504).bodyJson()
                .hasPathSatisfying("$.rule", v -> v.assertThat().isEqualTo("tool-timeout"))
                .hasPathSatisfying("$.title", v -> v.assertThat().isEqualTo("Tool timed out"));
    }

    @Test
    void aToolsOwnTimeoutBeatsTheDefault() {
        shopDelayMs = 800;   // fine for the 3 s default ...
        assertThat(call("support-bot", null, "getOrder", ORDER)).hasStatusOk();

        setToolTimeout("getOrder", 300);   // ... but too slow once the admin sets 300 ms (no restart)
        assertThat(call("support-bot", null, "getOrder", ORDER)).hasStatus(504);
    }

    @Test
    void repeatedFailuresOpenTheBreakerThenCallsFailFastAndTheToolIsHidden() {
        shopFailStatus = 503;
        assertThat(toolNamesFor("support-bot")).contains("getOrder");

        for (int i = 0; i < 5; i++) {
            assertThat(call("support-bot", null, "getOrder", ORDER)).hasStatus(502);
        }
        assertThat(received).hasSize(5);

        // OPEN: refused at once with 503, the fake shop is not called any more
        assertThat(call("support-bot", null, "getOrder", ORDER)).hasStatus(503).bodyJson()
                .hasPathSatisfying("$.rule", v -> v.assertThat().isEqualTo("circuit-open"));
        assertThat(received).hasSize(5);

        assertThat(toolNamesFor("support-bot")).doesNotContain("getOrder").contains("getCustomer", "issueRefund");
        JsonNode orderHealth = healthOf("getOrder");
        assertThat(orderHealth.get("state").asString()).isEqualTo("OPEN");
        assertThat(orderHealth.get("visibleToAgents").asBoolean()).isFalse();
        assertThat(healthOf("getCustomer").get("state").asString()).isEqualTo("CLOSED");
    }

    @Test
    void afterTheWaitTheToolIsHalfOpenVisibleAgainAndTrialCallsCloseTheBreaker() {
        shopFailStatus = 500;
        for (int i = 0; i < 5; i++) {
            call("support-bot", null, "getOrder", ORDER);
        }
        assertThat(toolBreakers.isOpen("getOrder")).isTrue();

        // Nobody calls the hidden tool, yet it moves to HALF_OPEN by itself after the wait.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(toolBreakers.breakerFor("getOrder").getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN));
        assertThat(toolNamesFor("support-bot")).contains("getOrder");

        shopFailStatus = 0;   // the tool recovered
        for (int i = 0; i < 3; i++) {
            assertThat(call("support-bot", null, "getOrder", ORDER)).hasStatusOk();
        }
        assertThat(toolBreakers.breakerFor("getOrder").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(toolNamesFor("support-bot")).contains("getOrder");
    }

    @Test
    void aFailingTrialCallOpensTheBreakerAgain() {
        shopFailStatus = 500;
        for (int i = 0; i < 5; i++) {
            call("support-bot", null, "getOrder", ORDER);
        }
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(toolBreakers.breakerFor("getOrder").getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN));

        for (int i = 0; i < 3; i++) {
            call("support-bot", null, "getOrder", ORDER);   // the tool is still broken
        }
        assertThat(toolBreakers.breakerFor("getOrder").getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(toolNamesFor("support-bot")).doesNotContain("getOrder");
    }

    @Test
    void aWriteToolIsCalledExactlyOnceWhenItFails() {
        shopFailStatus = 500;

        assertThat(call("support-bot", "1", "issueRefund", REFUND)).hasStatus(502);

        assertThat(received).hasSize(1);   // no retry: a second refund could be paid twice
    }

    @Test
    void aWriteToolThatTimesOutIsAlsoCalledOnceAndTheAgentIsWarned() {
        shopDelayMs = 2000;
        setToolTimeout("issueRefund", 300);

        assertThat(call("support-bot", "1", "issueRefund", REFUND)).hasStatus(504).bodyJson()
                .hasPathSatisfying("$.detail", v -> v.assertThat().asString().contains("do not repeat it blindly"));
        assertThat(received).hasSize(1);
    }

    @Test
    void clientMistakesAndNotFoundAnswersDoNotOpenTheBreaker() {
        shopFailStatus = 404;   // an ordinary answer ("no such order"), not a broken tool
        for (int i = 0; i < 8; i++) {
            assertThat(call("support-bot", null, "getOrder", ORDER)).hasStatus(404);
        }
        assertThat(toolBreakers.isOpen("getOrder")).isFalse();
    }

    // ---------- the agent tool list (ToolCatalogService) ----------

    @Test
    void theToolListShowsOnlyWhatThePolicyAllowsAndHidesKilledTools() {
        assertThat(toolNamesFor("support-bot")).containsExactlyInAnyOrder("getCustomer", "getOrder", "issueRefund");
        assertThat(toolNamesFor("analytics-bot")).containsExactlyInAnyOrder("getCustomer", "getOrder");

        setKillSwitch("/api/admin/kill-switch/tools/getOrder", true);
        assertThat(toolNamesFor("support-bot")).doesNotContain("getOrder");
        setKillSwitch("/api/admin/kill-switch/tools/getOrder", false);

        setKillSwitch("/api/admin/kill-switch/all-writes", true);
        assertThat(toolNamesFor("support-bot")).containsExactlyInAnyOrder("getCustomer", "getOrder");
    }

    // ---------- helpers ----------

    private void setToolTimeout(String toolName, int timeoutMs) {
        Tool tool = toolRepository.findByName(toolName).orElseThrow();
        tool.setTimeoutMs(timeoutMs);
        toolRepository.save(tool);
    }

    private List<String> toolNamesFor(String agent) {
        MvcTestResult result = mvc.get().uri("/api/agent/tools")
                .header("Authorization", "Bearer " + tokenFor(agent)).exchange();
        assertThat(result).hasStatusOk();
        List<String> names = new ArrayList<>();
        json(result).get("content").forEach(tool -> names.add(tool.get("name").asString()));
        return names;
    }

    private JsonNode healthOf(String toolName) {
        MvcTestResult result = mvc.get().uri("/api/admin/tools/health").with(admin()).exchange();
        assertThat(result).hasStatusOk();
        for (JsonNode row : json(result).get("content")) {
            if (row.get("name").asString().equals(toolName)) {
                return row;
            }
        }
        throw new AssertionError("no health row for " + toolName);
    }
}
