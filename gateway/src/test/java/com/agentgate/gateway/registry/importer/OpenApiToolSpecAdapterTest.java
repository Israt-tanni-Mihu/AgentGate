package com.agentgate.gateway.registry.importer;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentgate.gateway.registry.RiskLevel;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Plain unit test: no Spring, no database. Uses a copy of mock-tools' real /v3/api-docs. */
class OpenApiToolSpecAdapterTest {

    private final OpenApiToolSpecAdapter adapter = new OpenApiToolSpecAdapter(JsonMapper.builder().build());

    static String shopBdSpec() throws IOException {
        try (InputStream in = OpenApiToolSpecAdapterTest.class.getResourceAsStream("/openapi/shopbd-openapi.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void supportsOpenApi3ButNotOtherDocuments() throws IOException {
        assertThat(adapter.supports(shopBdSpec())).isTrue();
        assertThat(adapter.supports("{\"swagger\":\"2.0\"}")).isFalse();
        assertThat(adapter.supports("not json at all")).isFalse();
    }

    @Test
    void convertsEveryShopBdOperationIntoAToolDefinition() throws IOException {
        AdaptedSpec result = adapter.adapt(shopBdSpec());

        assertThat(result.skipped()).isEmpty();
        Map<String, ToolDefinition> byName = result.tools().stream()
                .collect(Collectors.toMap(ToolDefinition::name, Function.identity()));
        assertThat(byName).containsOnlyKeys("getCustomer", "getOrder", "issueRefund", "sendSms");

        ToolDefinition getOrder = byName.get("getOrder");
        assertThat(getOrder.httpMethod()).isEqualTo("GET");
        assertThat(getOrder.path()).isEqualTo("/api/orders/{id}");
        assertThat(getOrder.suggestedRiskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(getOrder.description()).startsWith("Read-only. Returns the order's items");
        assertThat(getOrder.inputSchema().at("/properties/id/type").asString()).isEqualTo("integer");
        assertThat(getOrder.inputSchema().get("required").toString()).isEqualTo("[\"id\"]");

        assertThat(byName.get("issueRefund").suggestedRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(byName.get("sendSms").suggestedRiskLevel()).isEqualTo(RiskLevel.MEDIUM);
    }

    @Test
    void requestBodyRefIsResolvedAndFlattenedIntoTheInputSchema() throws IOException {
        ToolDefinition refund = adapter.adapt(shopBdSpec()).tools().stream()
                .filter(tool -> tool.name().equals("issueRefund")).findFirst().orElseThrow();

        JsonNode schema = refund.inputSchema();
        assertThat(schema.get("type").asString()).isEqualTo("object");
        assertThat(schema.get("properties").propertyNames()).containsExactlyInAnyOrder("orderId", "amount", "reason");
        assertThat(schema.at("/properties/reason/maxLength").asInt()).isEqualTo(200);
        assertThat(schema.get("required").toString()).contains("orderId", "amount", "reason");
        assertThat(schema.get("additionalProperties").asBoolean()).isFalse();
        assertThat(schema.toString()).doesNotContain("$ref");
    }

    @Test
    void missingRiskLevelDefaultsToMediumAndMissingOperationIdIsSkipped() {
        String spec = """
                {"openapi": "3.0.3",
                 "paths": {
                   "/ping": {"get": {"operationId": "ping", "summary": "Ping"}},
                   "/nameless": {"post": {"summary": "No operationId here"}}
                 }}
                """;
        AdaptedSpec result = adapter.adapt(spec);

        assertThat(result.tools()).hasSize(1);
        assertThat(result.tools().getFirst().suggestedRiskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(result.tools().getFirst().description()).isEqualTo("Ping");
        assertThat(result.skipped()).containsExactly("POST /nameless: no operationId");
    }
}
