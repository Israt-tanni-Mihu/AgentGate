package com.agentgate.gateway.registry;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentgate.gateway.TestcontainersConfiguration;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Full application + real PostgreSQL (Testcontainers). The OpenAPI spec is served by a tiny JDK HTTP server
 * from test resources, so this test does not need mock-tools to be running.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@WithMockUser(roles = "ADMIN")   // every request runs as a logged-in admin
class ToolRegistryIntegrationTest {

    static HttpServer specServer;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ToolRepository repository;

    @Autowired
    ObjectMapper objectMapper;

    @BeforeAll
    static void startSpecServer() throws IOException {
        byte[] spec = ToolRegistryIntegrationTest.class.getResourceAsStream("/openapi/shopbd-openapi.json").readAllBytes();
        specServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        specServer.createContext("/v3/api-docs", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, spec.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(spec);
            }
        });
        specServer.start();
    }

    @AfterAll
    static void stopSpecServer() {
        specServer.stop(0);
    }

    @BeforeEach
    void emptyRegistry() {
        repository.deleteAll();
    }

    @Test
    void crudLifecycle() {
        MvcTestResult created = post("/api/admin/tools", ToolControllerTest.validBody());
        assertThat(created).hasStatus(201);
        long id = json(created).get("id").asLong();
        assertThat(created.getResponse().getHeader("Location")).isEqualTo("/api/admin/tools/" + id);

        assertThat(mvc.get().uri("/api/admin/tools/{id}", id))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.name", v -> v.assertThat().isEqualTo("getOrder"))
                .hasPathSatisfying("$.inputSchema.properties.id.type", v -> v.assertThat().isEqualTo("integer"))
                .hasPathSatisfying("$.enabled", v -> v.assertThat().isEqualTo(true));

        assertThat(post("/api/admin/tools", ToolControllerTest.validBody())).hasStatus(409);

        String changed = ToolControllerTest.validBody()
                .replace("\"LOW\"", "\"HIGH\"").replace("Get an order by id", "Changed");
        assertThat(mvc.put().uri("/api/admin/tools/{id}", id).contentType(MediaType.APPLICATION_JSON).content(changed))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.riskLevel", v -> v.assertThat().isEqualTo("HIGH"))
                .hasPathSatisfying("$.description", v -> v.assertThat().isEqualTo("Changed"));

        assertThat(mvc.delete().uri("/api/admin/tools/{id}", id)).hasStatus(204);
        assertThat(mvc.get().uri("/api/admin/tools/{id}", id)).hasStatus(404);
    }

    @Test
    void importCreatesToolsAndListIsPaged() {
        MvcTestResult result = post("/api/admin/tools/import", importBody());

        assertThat(result).hasStatusOk();
        JsonNode summary = json(result);
        assertThat(summary.get("format").asString()).isEqualTo("openapi-3");
        assertThat(summary.get("created").asInt()).isEqualTo(4);
        assertThat(summary.get("updated").asInt()).isZero();
        assertThat(summary.get("skipped").asInt()).isZero();

        // Sorted by name by default: getCustomer, getOrder, issueRefund, sendSms
        assertThat(mvc.get().uri("/api/admin/tools?page=0&size=2"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.content.length()", v -> v.assertThat().isEqualTo(2))
                .hasPathSatisfying("$.content[0].name", v -> v.assertThat().isEqualTo("getCustomer"))
                .hasPathSatisfying("$.page.totalElements", v -> v.assertThat().isEqualTo(4))
                .hasPathSatisfying("$.page.totalPages", v -> v.assertThat().isEqualTo(2));

        Tool refund = repository.findByName("issueRefund").orElseThrow();
        assertThat(refund.getRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(refund.getHttpMethod()).isEqualTo("POST");
        assertThat(refund.getPath()).isEqualTo("/api/refunds");
        assertThat(refund.getBaseUrl()).isEqualTo("http://localhost:8081");
        assertThat(refund.getOwnerTeam()).isEqualTo("shop-team");
    }

    @Test
    void importIsIdempotentAndKeepsAdminRiskDecision() {
        post("/api/admin/tools/import", importBody());

        // Second import of the same spec: nothing new, nothing duplicated.
        JsonNode second = json(post("/api/admin/tools/import", importBody()));
        assertThat(second.get("created").asInt()).isZero();
        assertThat(second.get("updated").asInt()).isZero();
        assertThat(second.get("skipped").asInt()).isEqualTo(4);
        assertThat(repository.count()).isEqualTo(4);

        // Admin raises getOrder to HIGH and edits its description by hand.
        Tool getOrder = repository.findByName("getOrder").orElseThrow();
        getOrder.setRiskLevel(RiskLevel.HIGH);
        getOrder.setDescription("edited by admin");
        repository.save(getOrder);

        // Third import refreshes the description from the spec but keeps the admin's risk level.
        JsonNode third = json(post("/api/admin/tools/import", importBody()));
        assertThat(third.get("updated").asInt()).isEqualTo(1);
        assertThat(third.get("skipped").asInt()).isEqualTo(3);
        Tool reimported = repository.findByName("getOrder").orElseThrow();
        assertThat(reimported.getDescription()).startsWith("Read-only.");
        assertThat(reimported.getRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(repository.count()).isEqualTo(4);
    }

    @Test
    void unreachableSpecGives502ProblemDetails() {
        String body = """
                {"specUrl": "http://localhost:%d/missing", "ownerTeam": "shop-team", "baseUrl": "http://localhost:8081"}
                """.formatted(specServer.getAddress().getPort());

        assertThat(post("/api/admin/tools/import", body))
                .hasStatus(502)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .hasPathSatisfying("$.title", v -> v.assertThat().isEqualTo("Tool spec import failed"));
    }

    private String importBody() {
        return """
                {"specUrl": "http://localhost:%d/v3/api-docs", "ownerTeam": "shop-team", "baseUrl": "http://localhost:8081"}
                """.formatted(specServer.getAddress().getPort());
    }

    private MvcTestResult post(String uri, String body) {
        return mvc.post().uri(uri).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private JsonNode json(MvcTestResult result) {
        try {
            return objectMapper.readTree(result.getResponse().getContentAsString());
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
