package com.agentgate.gateway.limits;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentgate.gateway.AgentCallIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** FR6 through the real HTTP pipeline: the 21st WRITE in an hour is blocked with 429 and an alert is stored. */
class LimitsIntegrationTest extends AgentCallIntegrationTest {

    private static final String REFUND = "{\"orderId\": 1001, \"amount\": 100, \"reason\": \"damaged\"}";

    @Test
    void twentyFirstWriteInAnHourIsBlockedWith429AndAnAlertIsStored() {
        for (int i = 0; i < 20; i++) {
            assertThat(call("support-bot", "1", "issueRefund", REFUND)).hasStatus(201);
        }

        var blocked = call("support-bot", "1", "issueRefund", REFUND);
        assertThat(blocked).hasStatus(429).hasContentType(MediaType.APPLICATION_PROBLEM_JSON).bodyJson()
                .hasPathSatisfying("$.title", v -> v.assertThat().isEqualTo("Too many calls"))
                .hasPathSatisfying("$.limit", v -> v.assertThat().isEqualTo("write-blast-radius-per-hour"))
                .hasPathSatisfying("$.max", v -> v.assertThat().isEqualTo(20))
                .hasPath("$.resetsAt");
        assertThat(blocked.getResponse().getHeader("Retry-After")).isNotBlank();

        // A second blocked call in the same hour is blocked too, but raises no second alert.
        assertThat(call("support-bot", "1", "issueRefund", REFUND)).hasStatus(429);
        assertThat(alertRepository.count()).isEqualTo(1);

        // Blocked calls never reached ShopBD: exactly the 20 allowed refunds arrived.
        assertThat(received).hasSize(20);
    }

    @Test
    void theAlertIsVisibleToTheAdminAndTheCapOnlyCountsWrites() {
        for (int i = 0; i < 20; i++) {
            call("support-bot", "1", "issueRefund", REFUND);
        }
        call("support-bot", "1", "issueRefund", REFUND);   // 21st write → alert

        assertThat(mvc.get().uri("/api/admin/alerts").with(admin()))
                .hasStatusOk().bodyJson()
                .hasPathSatisfying("$.page.totalElements", v -> v.assertThat().isEqualTo(1))
                .hasPathSatisfying("$.content[0].alertType", v -> v.assertThat().isEqualTo("LIMIT_BREACH"))
                .hasPathSatisfying("$.content[0].agentId", v -> v.assertThat().isEqualTo("support-bot"))
                .hasPathSatisfying("$.content[0].toolName", v -> v.assertThat().isEqualTo("issueRefund"))
                .hasPathSatisfying("$.content[0].message",
                        v -> v.assertThat().asString().contains("write-blast-radius-per-hour"));

        // READ calls are not counted in the write cap, so reads still work after the cap is reached.
        assertThat(call("support-bot", "1", "getOrder", "{\"id\": \"1001\"}")).hasStatusOk();
    }

    @Test
    void alertsRequireAdminCredentials() {
        assertThat(mvc.get().uri("/api/admin/alerts")).hasStatus(401);
    }
}
