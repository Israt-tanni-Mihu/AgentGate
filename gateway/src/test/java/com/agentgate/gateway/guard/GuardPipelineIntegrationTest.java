package com.agentgate.gateway.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;

import com.agentgate.gateway.TestcontainersConfiguration;
import com.agentgate.gateway.registry.RiskLevel;
import com.agentgate.gateway.registry.Tool;
import com.agentgate.gateway.registry.ToolKind;
import com.agentgate.gateway.registry.ToolRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * FR5 guards on the real call path: agent token → schema guard → policy → forward (fake ShopBD server)
 * → ownership check → redaction → truncation → agent. Uses the demo policy version 1 from "local":
 * support-bot is LOW clearance, analytics-bot is HIGH.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class GuardPipelineIntegrationTest {

    static final String CUSTOMER_1 = """
            {"id":1,"name":"Rahim Uddin","email":"rahim@example.com","phone":"01712345678",\
            "nationalId":"1234567890123","city":"Dhaka"}""";
    static final String ORDER_OF_CUSTOMER_1 = """
            {"id":1001,"customerId":1,"items":["Hilsa fish 1kg"],"total":1450.00,"currency":"BDT","status":"DELIVERED"}""";
    static final String ORDER_OF_CUSTOMER_2 = """
            {"id":2001,"customerId":2,"items":["Ilish fish 2kg (another customer)"],"total":2900.00,"currency":"BDT","status":"SHIPPED"}""";

    static final String CUSTOMER_SCHEMA = """
            {"type":"object","properties":{"id":{"type":"integer"}},"required":["id"],"additionalProperties":false}""";
    static final String ORDER_SCHEMA = CUSTOMER_SCHEMA;
    static final String REFUND_SCHEMA = """
            {"type":"object","properties":{"orderId":{"type":"integer"},"amount":{"type":"number"},"reason":{"type":"string"}},
             "required":["orderId","amount","reason"],"additionalProperties":false}""";

    static HttpServer shopServer;
    /** "METHOD path" of every request that reached the fake shop. */
    static final List<String> received = new CopyOnWriteArrayList<>();

    @Autowired MockMvcTester mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired ToolRepository toolRepository;
    @Autowired com.agentgate.gateway.limits.RateCounters rateCounters;

    @BeforeAll
    static void startFakeShop() throws IOException {
        shopServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        shopServer.createContext("/", GuardPipelineIntegrationTest::serve);
        shopServer.start();
    }

    @AfterAll
    static void stopFakeShop() {
        shopServer.stop(0);
    }

    private static void serve(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        received.add(method + " " + path);
        exchange.getRequestBody().readAllBytes();

        int status = 200;
        String answer;
        if (method.equals("POST")) {
            status = 201;
            answer = "{\"result\":\"created\"}";
        } else if (path.startsWith("/api/customers/")) {
            answer = CUSTOMER_1;
        } else if (path.equals("/api/orders/9999")) {
            answer = bigOrder();
        } else if (path.equals("/api/orders/2001")) {
            answer = ORDER_OF_CUSTOMER_2;
        } else {
            answer = ORDER_OF_CUSTOMER_1;
        }
        byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** About 25 KB of JSON: well above the 8 KB limit. */
    private static String bigOrder() {
        String items = IntStream.range(0, 1000)
                .mapToObj(i -> "\"Jamdani saree design number " + i + "\"")
                .collect(Collectors.joining(","));
        return "{\"id\":9999,\"customerId\":1,\"items\":[" + items + "]}";
    }

    @BeforeEach
    void registerShopTools() {
        received.clear();
        rateCounters.reset();   // FR6 counters are shared by the test context; start each test with none
        toolRepository.deleteAll();
        toolRepository.save(tool("getCustomer", "GET", "/api/customers/{id}", RiskLevel.LOW, CUSTOMER_SCHEMA));
        toolRepository.save(tool("getOrder", "GET", "/api/orders/{id}", RiskLevel.LOW, ORDER_SCHEMA));
        toolRepository.save(tool("issueRefund", "POST", "/api/refunds", RiskLevel.MEDIUM, REFUND_SCHEMA));
    }

    // ---------- schema guard (before the policy) ----------

    @Test
    void wrongTypeIsRejectedWith400AndNeverForwarded() {
        MvcTestResult result = call("support-bot", null, "issueRefund",
                "{\"orderId\": 1001, \"amount\": \"1500\", \"reason\": \"damaged\"}");

        assertThat(result).hasStatus(400).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(problems(result)).anySatisfy(p -> assertThat(p).contains("amount").contains("string"));
        assertThat(received).isEmpty();
    }

    @Test
    void missingRequiredFieldIsRejectedWith400() {
        MvcTestResult result = call("support-bot", null, "issueRefund", "{\"orderId\": 1001, \"amount\": 1500}");

        assertThat(result).hasStatus(400);
        assertThat(problems(result)).anySatisfy(p -> assertThat(p).contains("reason"));
        assertThat(received).isEmpty();
    }

    @Test
    void smuggledExtraFieldIsRejectedWith400() {
        MvcTestResult result = call("support-bot", null, "issueRefund",
                "{\"orderId\": 1001, \"amount\": 1500, \"reason\": \"damaged\", \"approvedBy\": \"admin\"}");

        assertThat(result).hasStatus(400).bodyJson()
                .hasPathSatisfying("$.title", v -> v.assertThat().isEqualTo("Invalid arguments"));
        assertThat(problems(result)).containsExactly("unknown argument 'approvedBy' (not in the tool's schema)");
        assertThat(received).isEmpty();
    }

    // ---------- ownership check (after forwarding) ----------

    @Test
    void crossCustomerReadIsFetchedButWithheldFromTheAgent() {
        MvcTestResult result = call("support-bot", "1", "getOrder", "{\"id\": 2001}");

        assertThat(result).hasStatus(403).hasContentType(MediaType.APPLICATION_PROBLEM_JSON).bodyJson()
                .hasPathSatisfying("$.rule", v -> v.assertThat().isEqualTo("ownership-check"))
                .hasPathSatisfying("$.detail", v -> v.assertThat().asString().contains("cross-customer read"));
        assertThat(json(result).toString()).doesNotContain("Ilish");
        assertThat(received).containsExactly("GET /api/orders/2001");
    }

    @Test
    void ownOrderIsAllowed() {
        assertThat(call("support-bot", "1", "getOrder", "{\"id\": 1001}")).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.customerId", v -> v.assertThat().isEqualTo(1));
    }

    // ---------- redaction by clearance ----------

    @Test
    void lowClearanceSupportBotSeesMaskedPhoneEmailAndNationalId() {
        MvcTestResult result = call("support-bot", "1", "getCustomer", "{\"id\": 1}");

        assertThat(result).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.name", v -> v.assertThat().isEqualTo("Rahim Uddin"))
                .hasPathSatisfying("$.email", v -> v.assertThat().isEqualTo("r***@example.com"))
                .hasPathSatisfying("$.phone", v -> v.assertThat().isEqualTo("017******78"))
                .hasPathSatisfying("$.nationalId", v -> v.assertThat().isEqualTo(maskedNationalId()));
    }

    @Test
    void highClearanceAnalyticsBotSeesEmailAndPhoneButNotNationalId() {
        MvcTestResult result = call("analytics-bot", null, "getCustomer", "{\"id\": 1}");

        assertThat(result).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.email", v -> v.assertThat().isEqualTo("rahim@example.com"))
                .hasPathSatisfying("$.phone", v -> v.assertThat().isEqualTo("01712345678"))
                .hasPathSatisfying("$.nationalId", v -> v.assertThat().isEqualTo(maskedNationalId()));
    }

    // ---------- truncation (outermost) ----------

    @Test
    void largeResultIsTruncatedWithANotice() throws Exception {
        MvcTestResult result = call("support-bot", null, "getOrder", "{\"id\": 9999}");

        assertThat(result).hasStatusOk();
        JsonNode body = json(result);
        assertThat(body.get("truncated").asBoolean()).isTrue();
        assertThat(body.get("limitBytes").asLong()).isEqualTo(8192);
        assertThat(body.get("originalSizeBytes").asLong()).isGreaterThan(8192);
        assertThat(body.get("notice").asString()).contains("8192 bytes");
        assertThat(body.get("partialResult").asString().getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(8192);
    }

    // ---------- helpers ----------

    private static String maskedNationalId() {
        return "12" + "*".repeat(9) + "23"; // 1234567890123 keeps its first and last two digits
    }

    private List<String> problems(MvcTestResult result) {
        List<String> problems = new ArrayList<>();
        json(result).get("problems").forEach(p -> problems.add(p.asString()));
        return problems;
    }

    private MvcTestResult call(String agent, String endUser, String toolName, String arguments) {
        var request = mvc.post().uri("/api/agent/calls/{tool}", toolName)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(agent))
                .contentType(MediaType.APPLICATION_JSON).content(arguments);
        if (endUser != null) {
            request = request.header("X-On-Behalf-Of", endUser);
        }
        return request.exchange();
    }

    private String tokenFor(String agent) {
        MvcTestResult result = mvc.post().uri("/oauth2/token")
                .with(httpBasic(agent, agent + "-local-secret"))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "client_credentials")
                .exchange();
        assertThat(result).hasStatusOk();
        return json(result).get("access_token").asString();
    }

    private JsonNode json(MvcTestResult result) {
        try {
            return objectMapper.readTree(result.getResponse().getContentAsString());
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Tool tool(String name, String method, String path, RiskLevel risk, String inputSchema) {
        Tool tool = new Tool();
        tool.setName(name);
        tool.setDescription(name);
        tool.setInputSchema(inputSchema);
        tool.setRiskLevel(risk);
        tool.setOwnerTeam("shop-team");
        tool.setKind(ToolKind.REST);
        tool.setHttpMethod(method);
        tool.setBaseUrl("http://localhost:" + shopServer.getAddress().getPort());
        tool.setPath(path);
        return tool;
    }
}
