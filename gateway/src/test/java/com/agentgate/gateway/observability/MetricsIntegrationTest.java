package com.agentgate.gateway.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;

import com.agentgate.gateway.AgentCallIntegrationTest;
import com.agentgate.gateway.registry.RiskLevel;
import com.agentgate.gateway.registry.Tool;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

/** C4: counters move as calls are allowed / denied / held, and the metrics endpoints are closed to agents. */
class MetricsIntegrationTest extends AgentCallIntegrationTest {

    private static final String REFUND = "{\"orderId\": 1001, \"amount\": 100, \"reason\": \"damaged\"}";

    @Autowired MeterRegistry meters;

    private double calls(String tool, String decision) {
        var counter = Search.in(meters).name("agentgate.calls").tag("tool", tool).tag("decision", decision).counter();
        return counter == null ? 0 : counter.count();
    }

    private long timedCalls() {
        return Search.in(meters).name("agentgate.pipeline.latency").timers().stream().mapToLong(t -> t.count()).sum();
    }

    private double blockedTotal() {
        return Search.in(meters).name("agentgate.calls.blocked").counters().stream().mapToDouble(c -> c.count()).sum();
    }

    @Test
    void countersMoveForAllowedDeniedAndHeldCalls() {
        double allowed = calls("getOrder", "allowed");
        double denied = calls("issueRefund", "denied");
        double held = calls("issueRefund", "held");
        double blocked = blockedTotal();
        long timed = timedCalls();

        assertThat(call("support-bot", "1", "getOrder", "{\"id\": \"1001\"}")).hasStatusOk();
        assertThat(call("analytics-bot", null, "issueRefund", REFUND)).hasStatus(403);   // not allowed by policy

        Tool refund = toolRepository.findByName("issueRefund").orElseThrow();
        refund.setRiskLevel(RiskLevel.HIGH);
        toolRepository.save(refund);
        assertThat(call("support-bot", "1", "issueRefund", REFUND)).hasStatus(403);      // held, nobody answers: expires

        assertThat(calls("getOrder", "allowed")).isEqualTo(allowed + 1);
        assertThat(calls("issueRefund", "denied")).isEqualTo(denied + 1);
        assertThat(calls("issueRefund", "held")).isEqualTo(held + 1);
        assertThat(blockedTotal()).isGreaterThan(blocked);   // at least the denied call, with its step as a tag
        assertThat(timedCalls())
                .isEqualTo(timed + 3);
        assertThat(meters.get("agentgate.approvals.pending").gauge().value()).isZero();   // the held call was decided
    }

    @Test
    void anUnknownToolNameDoesNotBecomeAMetricTag() {
        assertThat(call("support-bot", "1", "doesNotExist-" + System.nanoTime(), "{}")).hasStatus(404);

        assertThat(Search.in(meters).name("agentgate.calls").tag("tool", "unknown").counter()).isNotNull();
        assertThat(Search.in(meters).name("agentgate.calls").counters())
                .noneMatch(c -> String.valueOf(c.getId().getTag("tool")).startsWith("doesNotExist"));
    }

    @Test
    void prometheusIsForAdminsOnly() {
        assertThat(mvc.get().uri("/actuator/prometheus")).hasStatus(401);
        assertThat(mvc.get().uri("/actuator/prometheus")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor("support-bot"))).hasStatus(401);   // an agent token is no admin login
        assertThat(mvc.get().uri("/actuator/prometheus").with(httpBasic("approver", "approver-local-only")))
                .hasStatus(403);
        assertThat(mvc.get().uri("/actuator/prometheus").with(admin())).hasStatusOk().bodyText()
                .contains("agentgate_calls_total");
    }
}
