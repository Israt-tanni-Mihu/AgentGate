package com.agentgate.gateway.playground;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;

import com.agentgate.gateway.TestcontainersConfiguration;
import com.agentgate.gateway.policy.PolicyAdminService;
import com.agentgate.gateway.policy.PolicyStatus;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * FR9: for the same input, the playground must give the same decision as a REAL call through the pipeline,
 * must never reach the tool, and testing a draft must not change live traffic.
 * Uses the demo policy version 1 seeded in the "local" profile.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PolicyPlaygroundIntegrationTest {

    /** Fake ShopBD: just counts the requests it receives. */
    static HttpServer shopServer;
    static final AtomicInteger requestsToShop = new AtomicInteger();

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
            exchange.getRequestBody().readAllBytes();
            requestsToShop.incrementAndGet();
            byte[] answer = "{\"result\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, answer.length);
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
        requestsToShop.set(0);
        rateCounters.reset();   // FR6 counters are shared by the test context; start each test with none
        toolRepository.deleteAll();
        toolRepository.save(tool("getCustomer", "GET", "/api/customers/{id}", RiskLevel.LOW, """
                {"type":"object","properties":{"id":{"type":"integer"}},"required":["id"],"additionalProperties":false}"""));
        toolRepository.save(tool("issueRefund", "POST", "/api/refunds", RiskLevel.HIGH, """
                {"type":"object","properties":{"orderId":{"type":"integer"},"amount":{"type":"number"},"reason":{"type":"string"}},"required":["orderId","amount","reason"],"additionalProperties":false}"""));
        toolRepository.save(tool("sendSms", "POST", "/api/sms", RiskLevel.MEDIUM, """
                {"type":"object","properties":{"customerId":{"type":"integer"},"message":{"type":"string"}},"required":["customerId","message"],"additionalProperties":false}"""));
    }

    @AfterEach
    void restoreDemoPolicy() {
        policyService.setDryRun(1, false);
        policyService.activate(1);
    }

    // ---------- same decision as the real pipeline ----------

    @Test
    void allowedRefundOf1500() {
        JsonNode result = playgroundMatchesRealCall("support-bot", "1", "issueRefund",
                "{\"orderId\": 1001, \"amount\": 1500, \"reason\": \"damaged\"}");

        assertThat(result.get("decision").asString()).isEqualTo("ALLOW");
        assertThat(result.get("ruleId").asString()).isEqualTo("support-refund-up-to-2000");
        assertThat(result.get("policyVersion").asInt()).isEqualTo(1);
        assertThat(result.get("policyStatus").asString()).isEqualTo("ACTIVE");
    }

    @Test
    void deniedRefundOf5000ExplainsTheFailedCondition() {
        JsonNode result = playgroundMatchesRealCall("support-bot", "1", "issueRefund",
                "{\"orderId\": 1001, \"amount\": 5000, \"reason\": \"damaged\"}");

        assertThat(result.get("decision").asString()).isEqualTo("DENY");
        assertThat(result.get("ruleId").asString()).isEqualTo("default-deny");
        JsonNode refundCheck = check(result, "support-refund-up-to-2000");
        assertThat(refundCheck.get("result").asString()).isEqualTo("CONDITION_FAILED");
        assertThat(refundCheck.get("detail").asString()).contains("5000");
        assertThat(check(result, "support-own-customer-only").get("result").asString()).isEqualTo("NOT_FOR_THIS_CALL");
        assertThat(result.get("checks").size()).as("every rule of version 1 is listed").isEqualTo(4);
    }

    @Test
    void crossCustomerReadIsDenied() {
        JsonNode result = playgroundMatchesRealCall("support-bot", "1", "getCustomer", "{\"id\": 2}");

        assertThat(result.get("decision").asString()).isEqualTo("DENY");
        assertThat(check(result, "support-own-customer-only").get("result").asString()).isEqualTo("CONDITION_FAILED");
    }

    @Test
    void sendSmsIsDefaultDenied() {
        JsonNode result = playgroundMatchesRealCall("support-bot", "1", "sendSms",
                "{\"customerId\": 1, \"message\": \"hi\"}");

        assertThat(result.get("decision").asString()).isEqualTo("DENY");
        assertThat(result.get("ruleId").asString()).isEqualTo("default-deny");
    }

    // ---------- draft versions do not touch live traffic ----------

    @Test
    void testingADraftDoesNotAffectLiveTraffic() {
        int draft = createDraftAllowingSupportSms();

        JsonNode onDraft = playground("support-bot", "1", "sendSms", "{\"customerId\": 1, \"message\": \"hi\"}", draft);
        assertThat(onDraft.get("decision").asString()).isEqualTo("ALLOW");
        assertThat(onDraft.get("ruleId").asString()).isEqualTo("support-sms");
        assertThat(onDraft.get("policyVersion").asInt()).isEqualTo(draft);
        assertThat(onDraft.get("policyStatus").asString()).isEqualTo("DRAFT");

        // Live traffic still uses version 1, where sendSms is denied, and nothing changed in the database.
        assertThat(realCall("support-bot", "1", "sendSms", "{\"customerId\": 1, \"message\": \"hi\"}")).hasStatus(403);
        assertThat(playground("support-bot", "1", "sendSms", "{\"customerId\": 1}", null).get("decision").asString())
                .isEqualTo("DENY");
        assertThat(policyRepository.findByVersion(1).orElseThrow().getStatus()).isEqualTo(PolicyStatus.ACTIVE);
        assertThat(policyRepository.findByVersion(draft).orElseThrow().getStatus()).isEqualTo(PolicyStatus.DRAFT);
        assertThat(requestsToShop).as("the playground never forwards").hasValue(0);
    }

    // ---------- clear errors ----------

    @Test
    void unknownAgentToolOrVersionGiveProblemDetails() {
        assertThat(playgroundRaw("{\"agentId\": \"ghost-bot\", \"toolName\": \"sendSms\"}"))
                .hasStatus(404).hasContentType(MediaType.APPLICATION_PROBLEM_JSON).bodyJson()
                .hasPathSatisfying("$.title", v -> v.assertThat().isEqualTo("Agent not found"))
                .hasPathSatisfying("$.detail", v -> v.assertThat().asString().contains("ghost-bot"));

        assertThat(playgroundRaw("{\"agentId\": \"support-bot\", \"toolName\": \"launchRocket\"}"))
                .hasStatus(404).hasContentType(MediaType.APPLICATION_PROBLEM_JSON).bodyJson()
                .hasPathSatisfying("$.title", v -> v.assertThat().isEqualTo("Unknown tool"));

        assertThat(playgroundRaw("{\"agentId\": \"support-bot\", \"toolName\": \"sendSms\", \"policyVersion\": 999}"))
                .hasStatus(404).hasContentType(MediaType.APPLICATION_PROBLEM_JSON).bodyJson()
                .hasPathSatisfying("$.title", v -> v.assertThat().isEqualTo("Policy version not found"));

        assertThat(playgroundRaw("{\"agentId\": \"support-bot\", \"toolName\": \"sendSms\", \"endUser\": \"1, 2\"}"))
                .hasStatus(400).hasContentType(MediaType.APPLICATION_PROBLEM_JSON).bodyJson()
                .hasPath("$.errors.endUser");

        assertThat(mvc.post().uri("/api/admin/policies/playground").contentType(MediaType.APPLICATION_JSON)
                .content("{\"agentId\": \"support-bot\", \"toolName\": \"sendSms\"}"))
                .as("admin login required").hasStatus(401);
    }

    // ---------- helpers ----------

    /** Runs the same input through the playground AND the real pipeline and checks they agree. */
    private JsonNode playgroundMatchesRealCall(String agent, String endUser, String toolName, String arguments) {
        JsonNode playground = playground(agent, endUser, toolName, arguments, null);
        assertThat(requestsToShop).as("the playground never forwards").hasValue(0);

        MvcTestResult real = realCall(agent, endUser, toolName, arguments);
        int status = real.getResponse().getStatus();
        if (playground.get("decision").asString().equals("ALLOW")) {
            assertThat(status).as("real call allowed").isBetween(200, 299);
            assertThat(requestsToShop).hasValue(1);
        } else {
            assertThat(real).hasStatus(403).bodyJson()
                    .hasPathSatisfying("$.rule", v -> v.assertThat().isEqualTo(playground.get("ruleId").asString()))
                    .hasPathSatisfying("$.policyVersion",
                            v -> v.assertThat().isEqualTo(playground.get("policyVersion").asInt()))
                    .hasPathSatisfying("$.detail", v -> v.assertThat().isEqualTo(playground.get("reason").asString()));
            assertThat(requestsToShop).hasValue(0);
        }
        return playground;
    }

    private JsonNode playground(String agent, String endUser, String toolName, String arguments, Integer version) {
        var body = objectMapper.createObjectNode()
                .put("agentId", agent)
                .put("toolName", toolName)
                .put("endUser", endUser)
                .put("policyVersion", version);
        body.set("arguments", objectMapper.readTree(arguments));
        MvcTestResult result = playgroundRaw(objectMapper.writeValueAsString(body));
        assertThat(result).hasStatusOk();
        return json(result);
    }

    private MvcTestResult playgroundRaw(String body) {
        return mvc.post().uri("/api/admin/policies/playground").with(admin())
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private MvcTestResult realCall(String agent, String endUser, String toolName, String arguments) {
        var request = mvc.post().uri("/api/agent/calls/{tool}", toolName)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(agent))
                .contentType(MediaType.APPLICATION_JSON).content(arguments);
        if (endUser != null) {
            request = request.header("X-On-Behalf-Of", endUser);
        }
        return request.exchange();
    }

    private int createDraftAllowingSupportSms() {
        String body = """
                {"description": "draft: SMS for support-bot",
                 "rules": [{"id": "support-sms", "effect": "ALLOW", "agents": ["support-bot"], "tools": ["sendSms"]}]}
                """;
        MvcTestResult created = mvc.post().uri("/api/admin/policies").with(admin())
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
        assertThat(created).hasStatus(201);
        return json(created).get("version").asInt();
    }

    private static JsonNode check(JsonNode result, String ruleId) {
        for (JsonNode check : result.get("checks")) {
            if (check.get("ruleId").asString().equals(ruleId)) {
                return check;
            }
        }
        throw new AssertionError("no check for rule " + ruleId + " in " + result);
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

    private static RequestPostProcessor admin() {
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
