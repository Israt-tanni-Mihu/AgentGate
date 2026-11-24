package com.agentgate.gateway.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentgate.gateway.AgentCallIntegrationTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;

/** FR7 search and export through the real HTTP API. Four calls: 2 allowed, 2 denied, from 2 agents / 2 users. */
class AuditSearchIntegrationTest extends AgentCallIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired AuditWriter auditWriter;

    private MvcTestResult allowedCustomer1;
    private MvcTestResult deniedCustomer2;

    @BeforeEach
    void seedFourCalls() {
        auditWriter.flush();
        jdbc.update("DELETE FROM audit_records");
        allowedCustomer1 = call("support-bot", "1", "getCustomer", "{\"id\": 1}");
        deniedCustomer2 = call("support-bot", "1", "getCustomer", "{\"id\": 2}");   // someone else's record
        call("support-bot", "3", "getCustomer", "{\"id\": 3}");
        call("analytics-bot", null, "getOrder", "{\"id\": \"1\"}");
        auditWriter.flush();
    }

    @Test
    void withoutFiltersEverythingIsReturnedNewestFirst() {
        JsonNode page = search("");
        assertThat(page.get("totalElements").asInt()).isEqualTo(4);
        assertThat(page.get("content").get(0).get("seq").asInt()).isEqualTo(4);
        assertThat(page.get("content").get(3).get("seq").asInt()).isEqualTo(1);
    }

    @Test
    void filtersByAgentUserToolAndDecision() {
        assertThat(search("agentId=support-bot").get("totalElements").asInt()).isEqualTo(3);
        assertThat(search("agentId=analytics-bot").get("totalElements").asInt()).isEqualTo(1);
        assertThat(search("endUserId=1").get("totalElements").asInt()).isEqualTo(2);
        assertThat(search("toolName=getOrder").get("totalElements").asInt()).isEqualTo(1);
        assertThat(search("decision=DENIED").get("totalElements").asInt()).isEqualTo(1);
        assertThat(search("decision=denied").get("totalElements").asInt()).as("case-insensitive").isEqualTo(1);
        assertThat(search("decision=ALLOWED").get("totalElements").asInt()).isEqualTo(3);
    }

    @Test
    void filtersAreCombinedWithAnd() {
        JsonNode page = search("agentId=support-bot&endUserId=1&decision=DENIED&toolName=getCustomer");
        assertThat(page.get("totalElements").asInt()).isEqualTo(1);
        assertThat(page.get("content").get(0).get("ruleId").asString()).isEqualTo("default-deny");
        assertThat(search("agentId=analytics-bot&decision=DENIED").get("totalElements").asInt()).isZero();
    }

    @Test
    void filtersByTimeRange() {
        Instant future = Instant.now().plusSeconds(3600);
        Instant past = Instant.now().minusSeconds(3600);
        assertThat(search("from=" + past + "&to=" + future).get("totalElements").asInt()).isEqualTo(4);
        assertThat(search("from=" + future).get("totalElements").asInt()).isZero();
        assertThat(search("to=" + past).get("totalElements").asInt()).isZero();
    }

    @Test
    void fromAfterToIsAProblemDetail400() {
        MvcTestResult result = mvc.get().uri("/api/admin/audit?from=2026-12-01T00:00:00Z&to=2026-01-01T00:00:00Z")
                .with(admin()).exchange();
        assertThat(result).hasStatus(400);
        assertThat(json(result).get("detail").asString()).contains("'from' must not be after 'to'");
    }

    @Test
    void resultsArePaged() {
        JsonNode first = search("size=3&page=0");
        JsonNode second = search("size=3&page=1");
        assertThat(first.get("content").size()).isEqualTo(3);
        assertThat(second.get("content").size()).isEqualTo(1);
        assertThat(first.get("totalElements").asInt()).isEqualTo(4);
        assertThat(first.get("totalPages").asInt()).isEqualTo(2);
        assertThat(second.get("content").get(0).get("seq").asInt()).isEqualTo(1);
    }

    @Test
    void oneRecordIsFoundByTheCallIdFromTheResponseHeader() {
        String callId = deniedCustomer2.getResponse().getHeader("X-Call-Id");

        MvcTestResult result = mvc.get().uri("/api/admin/audit/{callId}", callId).with(admin()).exchange();

        assertThat(result).hasStatusOk();
        JsonNode record = json(result);
        assertThat(record.get("callId").asString()).isEqualTo(callId);
        assertThat(record.get("agentId").asString()).isEqualTo("support-bot");
        assertThat(record.get("endUserId").asString()).isEqualTo("1");
        assertThat(record.get("toolName").asString()).isEqualTo("getCustomer");
        assertThat(record.get("decision").asString()).isEqualTo("DENIED");
        assertThat(record.get("ruleId").asString()).isEqualTo("default-deny");
        assertThat(record.get("hash").asString()).hasSize(64);
        assertThat(allowedCustomer1.getResponse().getHeader("X-Call-Id")).isNotEqualTo(callId);
    }

    @Test
    void unknownCallIdIs404AndTheEndpointsNeedAdmin() {
        assertThat(mvc.get().uri("/api/admin/audit/{id}", UUID.randomUUID()).with(admin())).hasStatus(404);
        assertThat(mvc.get().uri("/api/admin/audit/not-a-uuid").with(admin())).hasStatus(400);
        assertThat(mvc.get().uri("/api/admin/audit")).hasStatus(401);
        assertThat(mvc.get().uri("/api/admin/audit/export")).hasStatus(401);
        assertThat(mvc.get().uri("/api/admin/audit/{id}", UUID.randomUUID())).hasStatus(401);
    }

    @Test
    void csvExportHasAHeaderOneLinePerRecordAndHonoursTheFilters() {
        MvcTestResult all = mvc.get().uri("/api/admin/audit/export?format=csv").with(admin()).exchange();
        assertThat(all).hasStatusOk();
        assertThat(all.getResponse().getContentType()).startsWith("text/csv");
        assertThat(all.getResponse().getHeader("Content-Disposition")).contains("audit-export.csv");
        String[] lines = body(all).split("\r\n");
        assertThat(lines[0]).startsWith("seq,callId,occurredAt,agentId,endUserId,toolName,arguments,decision");
        assertThat(lines).hasSize(5);   // header + 4 records, oldest first
        assertThat(lines[1]).startsWith("1,");
        assertThat(lines[4]).startsWith("4,");

        MvcTestResult denied = mvc.get().uri("/api/admin/audit/export?decision=DENIED").with(admin()).exchange();
        String[] deniedLines = body(denied).split("\r\n");
        assertThat(deniedLines).hasSize(2);
        assertThat(deniedLines[1]).contains("default-deny").contains("support-bot");
    }

    @Test
    void csvQuotesCellsWithCommasAndQuotes() {
        // the stored arguments are JSON, so they contain commas and double quotes
        String body = body(mvc.get().uri("/api/admin/audit/export?toolName=getOrder").with(admin()).exchange());
        assertThat(body).contains("\"{\"\"id\"\":\"\"1\"\"}\"");
    }

    @Test
    void jsonExportIsAnArrayAndHonoursTheFilters() throws Exception {
        MvcTestResult result = mvc.get().uri("/api/admin/audit/export?format=json&agentId=support-bot&endUserId=1")
                .with(admin()).exchange();
        assertThat(result).hasStatusOk();
        assertThat(result.getResponse().getContentType()).startsWith("application/json");
        JsonNode array = objectMapper.readTree(body(result));
        assertThat(array.isArray()).isTrue();
        assertThat(array.size()).isEqualTo(2);
        assertThat(array.get(0).get("seq").asInt()).isEqualTo(1);

        MvcTestResult none = mvc.get().uri("/api/admin/audit/export?format=json&agentId=nobody").with(admin()).exchange();
        assertThat(body(none)).isEqualTo("[]");
    }

    @Test
    void unknownExportFormatIsAProblemDetail400() {
        MvcTestResult result = mvc.get().uri("/api/admin/audit/export?format=xml").with(admin()).exchange();
        assertThat(result).hasStatus(400);
        assertThat(json(result).get("detail").asString()).contains("csv").contains("json");
    }

    @Test
    void csvCellsThatLookLikeFormulasAreDefused() {
        AuditExporter exporter = new AuditExporter(objectMapper);
        var out = new java.io.StringWriter();
        var writer = exporter.csv(out);
        writer.write(new AuditRecordResponse(1, UUID.randomUUID(), Instant.now(), "=cmd|' /C calc'!A0", null, "t", "{}",
                "ALLOWED", null, null, null, null, null, null, null, 1, 200, null, "p", "h"));
        writer.finish();
        assertThat(out.toString()).contains("'=cmd|' /C calc'!A0");
    }

    // ---------- helpers ----------

    private JsonNode search(String query) {
        MvcTestResult result = mvc.get().uri("/api/admin/audit?" + query).with(admin()).exchange();
        assertThat(result).hasStatusOk();
        return json(result);
    }

    private static String body(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
