package com.agentgate.gateway.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;

import com.agentgate.gateway.TestcontainersConfiguration;
import com.agentgate.gateway.policy.PolicyAdminService;
import com.agentgate.gateway.policy.PolicyStatus;
import com.agentgate.gateway.policy.PolicyVersion;
import com.agentgate.gateway.policy.PolicyVersionRepository;
import com.agentgate.gateway.registry.RiskLevel;
import com.agentgate.gateway.registry.Tool;
import com.agentgate.gateway.registry.ToolKind;
import com.agentgate.gateway.registry.ToolRepository;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The whole call path: agent token → CallController → PolicyStep → ForwardStep → a fake ShopBD server.
 * The fake server is a tiny JDK HTTP server that records every request it receives, so we can prove
 * that allowed calls arrive and denied calls never do. Uses the demo policy version 1 seeded in "local".
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class CallPipelineIntegrationTest {

    record ReceivedRequest(String method, String rawPath, String rawQuery, String body) {
    }

    static HttpServer shopServer;
    static final List<ReceivedRequest> received = new CopyOnWriteArrayList<>();

    @Autowired MockMvcTester mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired ToolRepository toolRepository;
    @Autowired PolicyVersionRepository policyRepository;
    @Autowired PolicyAdminService policyService;
    @Autowired com.agentgate.gateway.limits.RateCounters rateCounters;

    @BeforeAll
    static void startFakeShop() throws IOException {
        shopServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        shopServer.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new ReceivedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
                    exchange.getRequestURI().getRawQuery(), body));
            boolean isWrite = exchange.getRequestMethod().equals("POST");
            byte[] answer = (isWrite ? "{\"result\":\"created\"}" : "{\"result\":\"ok\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(isWrite ? 201 : 200, answer.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(answer);
            }
        });
        shopServer.start();
    }

    @AfterAll
    static void stopFakeShop() {
        shopServer.stop(0);
    }

    @BeforeEach
    void registerShopTools() {
        received.clear();
        rateCounters.reset();   // FR6 counters are shared by the test context; start each test with none
        toolRepository.deleteAll();
        // Schemas mirror the ShopBD OpenAPI spec. getOrder's id is a string here so the path-escaping test
        // can send a slash; with an integer id the schema guard would reject it first (see guard tests).
        toolRepository.save(tool("getCustomer", "GET", "/api/customers/{id}", RiskLevel.LOW, """
                {"type":"object","properties":{"id":{"type":"integer"}},"required":["id"],"additionalProperties":false}"""));
        toolRepository.save(tool("getOrder", "GET", "/api/orders/{id}", RiskLevel.LOW, """
                {"type":"object","properties":{"id":{"type":"string"}},"required":["id"],"additionalProperties":false}"""));
        toolRepository.save(tool("issueRefund", "POST", "/api/refunds", RiskLevel.HIGH, """
                {"type":"object","properties":{"orderId":{"type":"integer"},"amount":{"type":"number"},"reason":{"type":"string"}},"required":["orderId","amount","reason"],"additionalProperties":false}"""));
        toolRepository.save(tool("sendSms", "POST", "/api/sms", RiskLevel.MEDIUM, """
                {"type":"object","properties":{"customerId":{"type":"integer"},"message":{"type":"string"}},"required":["customerId","message"],"additionalProperties":false}"""));
    }

    /** Tests change the active policy; put the seeded demo version 1 back (not dry-run) after each one. */
    @AfterEach
    void restoreDemoPolicy() {
        policyService.setDryRun(1, false);
        policyService.activate(1);
    }

    // ---------- allowed calls reach the tool ----------

    @Test
    void allowedReadIsForwardedWithPathVariable() {
        MvcTestResult result = call("support-bot", null, "getOrder", "{\"id\": \"1001\"}");

        assertThat(result).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.result", v -> v.assertThat().isEqualTo("ok"));
        assertThat(received).singleElement().satisfies(r -> {
            assertThat(r.method()).isEqualTo("GET");
            assertThat(r.rawPath()).isEqualTo("/api/orders/1001");
        });
    }

    @Test
    void refundOf1500IsAllowedAndSentAsJsonBody() {
        MvcTestResult result = call("support-bot", "1", "issueRefund",
                "{\"orderId\": 1001, \"amount\": 1500, \"reason\": \"damaged\"}");

        assertThat(result).hasStatus(201);
        assertThat(received).singleElement().satisfies(r -> {
            assertThat(r.method()).isEqualTo("POST");
            assertThat(r.rawPath()).isEqualTo("/api/refunds");
            assertThat(objectMapper.readTree(r.body()).get("amount").asInt()).isEqualTo(1500);
        });
    }

    // ---------- denied calls never reach the tool ----------

    @Test
    void refundOf5000IsDeniedAndTheRuleIsShown() {
        MvcTestResult result = call("support-bot", "1", "issueRefund",
                "{\"orderId\": 1001, \"amount\": 5000, \"reason\": \"damaged\"}");

        assertThat(result).hasStatus(403).hasContentType(MediaType.APPLICATION_PROBLEM_JSON).bodyJson()
                .hasPathSatisfying("$.title", v -> v.assertThat().isEqualTo("Call denied"))
                .hasPathSatisfying("$.rule", v -> v.assertThat().isEqualTo("default-deny"))
                .hasPathSatisfying("$.policyVersion", v -> v.assertThat().isEqualTo(1))
                .hasPathSatisfying("$.detail", v -> v.assertThat().asString()
                        .contains("support-refund-up-to-2000").contains("5000"));
        assertThat(received).isEmpty();
    }

    @Test
    void crossCustomerReadIsDeniedButOwnCustomerIsAllowed() {
        assertThat(call("support-bot", "1", "getCustomer", "{\"id\": 2}"))
                .hasStatus(403).bodyJson()
                .hasPathSatisfying("$.detail", v -> v.assertThat().asString().contains("support-own-customer-only"));
        assertThat(received).isEmpty();

        assertThat(call("support-bot", "1", "getCustomer", "{\"id\": 1}")).hasStatusOk();
        assertThat(received).singleElement().satisfies(r -> assertThat(r.rawPath()).isEqualTo("/api/customers/1"));
    }

    @Test
    void sendSmsHasNoRuleSoItIsDefaultDenied() {
        assertThat(call("support-bot", "1", "sendSms", "{\"customerId\": 1, \"message\": \"hi\"}"))
                .hasStatus(403).bodyJson()
                .hasPathSatisfying("$.rule", v -> v.assertThat().isEqualTo("default-deny"));
        assertThat(call("analytics-bot", null, "sendSms", "{\"customerId\": 1, \"message\": \"hi\"}"))
                .hasStatus(403);
        assertThat(received).isEmpty();
    }

    @Test
    void pathSmugglingIsEscaped() {
        assertThat(call("support-bot", null, "getOrder", "{\"id\": \"1/../../admin\"}")).hasStatusOk();

        assertThat(received).singleElement().satisfies(r -> assertThat(r.rawPath()).isEqualTo("/api/orders/1%2F..%2F..%2Fadmin"));
    }

    @Test
    void unknownAndDisabledToolsGiveClearErrors() {
        assertThat(call("support-bot", null, "launchRocket", "{}"))
                .hasStatus(404).hasContentType(MediaType.APPLICATION_PROBLEM_JSON).bodyJson()
                .hasPathSatisfying("$.title", v -> v.assertThat().isEqualTo("Unknown tool"));

        Tool getOrder = toolRepository.findByName("getOrder").orElseThrow();
        getOrder.setEnabled(false);
        toolRepository.save(getOrder);
        assertThat(call("support-bot", null, "getOrder", "{\"id\": 1001}"))
                .hasStatus(403).bodyJson()
                .hasPathSatisfying("$.title", v -> v.assertThat().isEqualTo("Tool disabled"));
        assertThat(received).isEmpty();
    }

    @Test
    void callsNeedAnAgentToken() {
        assertThat(mvc.post().uri("/api/agent/calls/getOrder").contentType(MediaType.APPLICATION_JSON).content("{\"id\":1}"))
                .hasStatus(401);
        assertThat(received).isEmpty();
    }

    // ---------- versions, hot reload, dry-run ----------

    @Test
    void newVersionTakesEffectRightAfterActivation() {
        int draft = createDraftAllowingSupportSms();

        // A draft changes nothing...
        assertThat(call("support-bot", "1", "sendSms", "{\"customerId\": 1, \"message\": \"hi\"}")).hasStatus(403);

        // ...until it is activated through the admin API — no restart.
        assertThat(mvc.post().uri("/api/admin/policies/{v}/activate", draft).with(admin()))
                .hasStatusOk().bodyJson()
                .hasPathSatisfying("$.status", v -> v.assertThat().isEqualTo("ACTIVE"));
        assertThat(call("support-bot", "1", "sendSms", "{\"customerId\": 1, \"message\": \"hi\"}")).hasStatus(201);
        assertThat(policyRepository.findByVersion(1).orElseThrow().getStatus()).isEqualTo(PolicyStatus.RETIRED);
    }

    @Test
    void changeMadeDirectlyInTheDatabaseIsPickedUpWithin10Seconds() throws InterruptedException {
        int draft = createDraftAllowingSupportSms();
        // Simulate another gateway instance activating a version: write to the DB without touching our cache.
        PolicyVersion v1 = policyRepository.findByVersion(1).orElseThrow();
        v1.setStatus(PolicyStatus.RETIRED);
        policyRepository.saveAndFlush(v1);
        PolicyVersion next = policyRepository.findByVersion(draft).orElseThrow();
        next.setStatus(PolicyStatus.ACTIVE);
        policyRepository.saveAndFlush(next);

        long start = System.currentTimeMillis();
        int status = 403;
        while (status == 403 && System.currentTimeMillis() - start < 10_000) {
            Thread.sleep(250);
            status = call("support-bot", "1", "sendSms", "{\"customerId\": 1, \"message\": \"hi\"}")
                    .getResponse().getStatus();
        }
        assertThat(status).as("sendSms allowed within 10 s by the scheduled refresh").isEqualTo(201);
    }

    @Test
    void dryRunLetsWouldBeDeniedCallThroughAndLogsIt(CapturedOutput output) {
        assertThat(mvc.put().uri("/api/admin/policies/1/dry-run").with(admin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\": true}"))
                .hasStatusOk().bodyJson()
                .hasPathSatisfying("$.dryRun", v -> v.assertThat().isEqualTo(true));

        assertThat(call("support-bot", "1", "issueRefund", "{\"orderId\": 1001, \"amount\": 5000, \"reason\": \"x\"}"))
                .hasStatus(201);
        assertThat(received).hasSize(1);
        assertThat(output.getOut()).contains("DRY-RUN would DENY agent=support-bot")
                .contains("tool=issueRefund").contains("rule=default-deny");
    }

    // ---------- admin API ----------

    @Test
    void adminApiRejectsUnknownOperatorAndListsVersions() {
        String bad = """
                {"rules": [{"id": "r1", "effect": "ALLOW", "agents": ["support-bot"], "tools": ["getOrder"],
                            "conditions": [{"argument": "id", "operator": "looksLike", "value": 1}]}]}
                """;
        assertThat(mvc.post().uri("/api/admin/policies").with(admin()).contentType(MediaType.APPLICATION_JSON).content(bad))
                .hasStatus(400).bodyJson()
                .hasPathSatisfying("$.problems[0]", v -> v.assertThat().asString().contains("unknown operator 'looksLike'"));

        assertThat(mvc.get().uri("/api/admin/policies?sort=version,asc&size=1").with(admin()))
                .hasStatusOk().bodyJson()
                .hasPath("$.page.totalElements")
                .hasPathSatisfying("$.content[0].version", v -> v.assertThat().isEqualTo(1))
                .hasPathSatisfying("$.content[0].status", v -> v.assertThat().isEqualTo("ACTIVE"))
                .hasPathSatisfying("$.content[0].rules.length()", v -> v.assertThat().isEqualTo(4));
        assertThat(mvc.get().uri("/api/admin/policies/999").with(admin())).hasStatus(404);
        assertThat(mvc.get().uri("/api/admin/policies")).hasStatus(401);
    }

    // ---------- helpers ----------

    private int createDraftAllowingSupportSms() {
        String body = """
                {"description": "v1 + SMS for support-bot",
                 "rules": [
                   {"id": "support-read-orders", "effect": "ALLOW", "agents": ["support-bot"], "tools": ["getOrder"]},
                   {"id": "support-sms", "effect": "ALLOW", "agents": ["support-bot"], "tools": ["sendSms"]}
                 ]}
                """;
        MvcTestResult created = mvc.post().uri("/api/admin/policies").with(admin())
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
        assertThat(created).hasStatus(201).bodyJson()
                .hasPathSatisfying("$.status", v -> v.assertThat().isEqualTo("DRAFT"));
        return json(created).get("version").asInt();
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

    private static org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
        return httpBasic("admin", "admin-local-only");
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
        tool.setInputSchema(inputSchema.strip());
        tool.setRiskLevel(risk);
        tool.setOwnerTeam("shop-team");
        tool.setKind(ToolKind.REST);
        tool.setHttpMethod(method);
        tool.setBaseUrl("http://localhost:" + shopServer.getAddress().getPort());
        tool.setPath(path);
        return tool;
    }
}
