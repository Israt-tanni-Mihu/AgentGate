package com.agentgate.gateway.failsafe;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentgate.gateway.AgentCallIntegrationTest;
import com.agentgate.gateway.audit.AuditVerification;
import com.agentgate.gateway.audit.AuditVerifier;
import com.agentgate.gateway.audit.AuditWriter;
import com.agentgate.gateway.identity.AgentDirectory;
import com.agentgate.gateway.policy.ActivePolicyCache;
import com.agentgate.gateway.registry.RiskLevel;
import com.agentgate.gateway.registry.ToolDirectory;
import com.agentgate.gateway.registry.ToolRepository;
import com.agentgate.gateway.shared.failsafe.PolicyStoreState;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * QA4: when the policy store (PostgreSQL) is unreachable, MEDIUM/HIGH tools fail closed, LOW tools keep working on
 * the last known policies for up to max-stale (5 min), and audit records wait in memory and are written in order
 * when the database is back.
 */
@Import(OutageTestConfig.class)
class PolicyStoreFailSafeIntegrationTest extends AgentCallIntegrationTest {

    private static final String REFUND = "{\"orderId\": 1001, \"amount\": 100, \"reason\": \"damaged\"}";

    @Autowired ActivePolicyCache policyCache;
    @Autowired ToolDirectory toolDirectory;
    @Autowired AgentDirectory agentDirectory;
    @Autowired AuditWriter auditWriter;
    @Autowired AuditVerifier auditVerifier;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry meters;
    @Autowired ToolRepository tools;

    private String token;

    @BeforeEach
    void startHealthyWithEverythingKnown() {
        OutageTestConfig.databaseUp();
        OutageTestConfig.CLOCK.reset();
        auditWriter.flush();
        jdbc.update("DELETE FROM audit_records");
        policyCache.refresh();
        // What a gateway that has been running for a while already knows (the scheduled warm-up does this):
        token = tokenFor("support-bot");
        toolDirectory.findByName("getCustomer");
        toolDirectory.findByName("issueRefund");
        agentDirectory.findByClientId("support-bot");
    }

    @AfterEach
    void bringTheDatabaseBack() {
        OutageTestConfig.databaseUp();
        OutageTestConfig.CLOCK.reset();
        policyCache.refresh();
    }

    @Test
    void storeDown_refundFailsClosed_butLowRiskReadStillWorks() {
        outage();

        MvcTestResult read = callWith(token, "1", "getCustomer", "{\"id\": 1}");
        MvcTestResult refund = callWith(token, "1", "issueRefund", REFUND);

        assertThat(read).hasStatusOk();                      // low risk: last known policies, valid token, no database
        assertThat(refund).hasStatus(503);                   // medium risk: fail closed
        assertThat(json(refund).get("rule").asString()).isEqualTo("fail-closed-policy-store");
        assertThat(json(refund).get("detail").asString()).contains("policy store unavailable, failing closed");
        assertThat(received).noneMatch(r -> r.rawPath().contains("refunds"));   // the shop never saw the refund
        assertThat(policyCache.state()).isEqualTo(PolicyStoreState.STALE);
    }

    @Test
    void storeDownLongerThanMaxStale_lowRiskFailsClosedToo() {
        outage();
        assertThat(callWith(token, "1", "getCustomer", "{\"id\": 1}")).hasStatusOk();

        OutageTestConfig.CLOCK.advance(Duration.ofMinutes(6));   // max-stale is 5 minutes
        MvcTestResult read = callWith(token, "1", "getCustomer", "{\"id\": 1}");

        assertThat(read).hasStatus(503);
        assertThat(json(read).get("rule").asString()).isEqualTo("fail-closed-policy-store");
        assertThat(json(read).get("detail").asString()).contains("older than");
    }

    @Test
    void storeBack_everythingIsNormal_queuedAuditRecordsAreWrittenInOrder_chainVerifies() {
        outage();
        MvcTestResult first = callWith(token, "1", "getCustomer", "{\"id\": 1}");
        MvcTestResult second = callWith(token, "1", "issueRefund", REFUND);   // 503
        MvcTestResult third = callWith(token, "1", "getCustomer", "{\"id\": 1}");
        assertThat(auditWriter.queuedCount()).isEqualTo(3);                   // waiting in memory, none lost

        OutageTestConfig.databaseUp();
        policyCache.refresh();
        auditWriter.flush();

        assertThat(policyCache.state()).isEqualTo(PolicyStoreState.HEALTHY);
        assertThat(callWith(token, "1", "issueRefund", REFUND)).hasStatus(201);   // refund works again
        auditWriter.flush();

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT call_id::text AS call_id, rule_id, http_status FROM audit_records ORDER BY seq");
        List<String> expectedOrder = new ArrayList<>(List.of(callId(first), callId(second), callId(third)));
        assertThat(rows.stream().map(r -> (String) r.get("call_id")).limit(3).toList()).isEqualTo(expectedOrder);
        assertThat(rows.get(0)).containsEntry("rule_id", "stale-policy-allowed").containsEntry("http_status", 200);
        assertThat(rows.get(1)).containsEntry("rule_id", "fail-closed-policy-store").containsEntry("http_status", 503);
        assertThat(rows).hasSize(4);
        AuditVerification verification = auditVerifier.verify();
        assertThat(verification.status()).isEqualTo(AuditVerification.Status.OK);
        assertThat(verification.records()).isEqualTo(4);
    }

    @Test
    void healthShowsStaleAndMetricIsOne() {
        outage();

        MvcTestResult health = mvc.get().uri("/actuator/health").exchange();

        assertThat(json(health).at("/components/policyStore/status").asString()).isEqualTo("STALE");
        assertThat(meters.get("agentgate.policy.store.stale").gauge().value()).isEqualTo(1.0);

        OutageTestConfig.databaseUp();
        policyCache.refresh();
        assertThat(json(mvc.get().uri("/actuator/health").exchange()).at("/components/policyStore/status").asString())
                .isEqualTo("UP");
        assertThat(meters.get("agentgate.policy.store.stale").gauge().value()).isEqualTo(0.0);
    }

    /** The database goes away; the next policy refresh (every 5 s in real life) notices it. */
    private void outage() {
        OutageTestConfig.databaseDown();
        policyCache.refresh();
        assertThat(policyCache.state()).isEqualTo(PolicyStoreState.STALE);
    }

    /** Like {@code call(...)} but with a token we already hold: new tokens cannot be issued without the database. */
    private MvcTestResult callWith(String bearer, String endUser, String toolName, String arguments) {
        return mvc.post().uri("/api/agent/calls/{tool}", toolName)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                .header("X-On-Behalf-Of", endUser)
                .contentType(MediaType.APPLICATION_JSON).content(arguments).exchange();
    }

    private static String callId(MvcTestResult result) {
        return result.getResponse().getHeader("X-Call-Id");
    }
}
