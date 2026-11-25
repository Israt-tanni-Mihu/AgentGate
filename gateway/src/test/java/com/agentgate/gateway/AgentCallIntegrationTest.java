package com.agentgate.gateway;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;

import com.agentgate.gateway.admin.KillSwitchRepository;
import com.agentgate.gateway.admin.KillSwitchState;
import com.agentgate.gateway.health.ToolBreakers;
import com.agentgate.gateway.limits.AlertRepository;
import com.agentgate.gateway.limits.RateCounters;
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
 * Shared setup for integration tests that send calls through the whole pipeline to a fake ShopBD server.
 *
 * Shared Spring context, so the in-memory state (rate counters, kill switches) is reset before and after
 * every test. Otherwise one test would use up the quota or switch off a tool for the next one.
 */
@SpringBootTest(properties = {"agentgate.approval.timeout=3s", "agentgate.approval.webhook-url=",
        // FR10: a short open time so the health tests run in seconds (the default timeout stays 3 s)
        "agentgate.resilience.wait-in-open-state=1s"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public abstract class AgentCallIntegrationTest {

    protected record ReceivedRequest(String method, String rawPath, String body) {
    }

    private static HttpServer shopServer;
    /** Every request that really reached the fake shop. Blocked calls must not appear here. */
    protected static final List<ReceivedRequest> received = new CopyOnWriteArrayList<>();
    /** FR10: make the fake shop slow or broken. Reset before every test. */
    protected static volatile long shopDelayMs;
    protected static volatile int shopFailStatus;   // 0 = answer normally

    @Autowired protected MockMvcTester mvc;
    @Autowired protected ObjectMapper objectMapper;
    @Autowired protected ToolRepository toolRepository;
    @Autowired protected AlertRepository alertRepository;
    @Autowired protected RateCounters rateCounters;
    @Autowired protected KillSwitchRepository killSwitchRepository;
    @Autowired protected KillSwitchState killSwitchState;
    @Autowired protected ToolBreakers toolBreakers;

    @BeforeAll
    static void startFakeShop() throws IOException {
        shopServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        shopServer.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new ReceivedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(), body));
            if (shopDelayMs > 0) {
                try {
                    Thread.sleep(shopDelayMs);
                } catch (InterruptedException e) {
                    return;   // the gateway gave up waiting
                }
            }
            if (shopFailStatus != 0) {
                exchange.sendResponseHeaders(shopFailStatus, -1);
                exchange.close();
                return;
            }
            boolean isWrite = exchange.getRequestMethod().equals("POST");
            // Like the real ShopBD answers: an order names its customer, a customer profile has its own id.
            // (The ownership guard checks these fields when a call acts for an end user.)
            String path = exchange.getRequestURI().getRawPath();
            String answerText = isWrite ? "{\"result\":\"created\"}"
                    : path.startsWith("/api/customers/")
                    ? "{\"result\":\"ok\",\"id\":" + path.substring("/api/customers/".length()) + "}"
                    : path.startsWith("/api/orders/") ? "{\"result\":\"ok\",\"customerId\":1}" : "{\"result\":\"ok\"}";
            byte[] answer = answerText.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(isWrite ? 201 : 200, answer.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(answer);
            }
        });
        // Many threads: a slow answer (FR10 tests) must not block the next request.
        shopServer.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        shopServer.start();
    }

    @AfterAll
    static void stopFakeShop() {
        shopServer.stop(0);
    }

    @BeforeEach
    void resetStateAndRegisterTools() {
        received.clear();
        shopDelayMs = 0;
        shopFailStatus = 0;
        toolBreakers.reset();
        rateCounters.reset();
        alertRepository.deleteAll();
        killSwitchRepository.deleteAll();
        killSwitchState.reload();
        toolRepository.deleteAll();
        toolRepository.save(tool("getCustomer", "GET", "/api/customers/{id}", RiskLevel.LOW));
        toolRepository.save(tool("getOrder", "GET", "/api/orders/{id}", RiskLevel.LOW));
        toolRepository.save(tool("issueRefund", "POST", "/api/refunds", RiskLevel.MEDIUM));
    }

    @AfterEach
    void leaveNoStateBehind() {
        shopDelayMs = 0;
        shopFailStatus = 0;
        toolBreakers.reset();
        rateCounters.reset();
        killSwitchRepository.deleteAll();
        killSwitchState.reload();
    }

    // ---------- helpers for subclasses ----------

    protected MvcTestResult call(String agent, String endUser, String toolName, String arguments) {
        var request = mvc.post().uri("/api/agent/calls/{tool}", toolName)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(agent))
                .contentType(MediaType.APPLICATION_JSON).content(arguments);
        if (endUser != null) {
            request = request.header("X-On-Behalf-Of", endUser);
        }
        return request.exchange();
    }

    protected MvcTestResult setKillSwitch(String uri, boolean engaged) {
        return mvc.put().uri(uri).with(admin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"engaged\": " + engaged + "}")
                .exchange();
    }

    protected JsonNode json(MvcTestResult result) {
        try {
            return objectMapper.readTree(result.getResponse().getContentAsString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected static RequestPostProcessor admin() {
        return httpBasic("admin", "admin-local-only");
    }

    protected String tokenFor(String agent) {
        MvcTestResult result = mvc.post().uri("/oauth2/token")
                .with(httpBasic(agent, agent + "-local-secret"))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "client_credentials")
                .exchange();
        return json(result).get("access_token").asString();
    }

    private static Tool tool(String name, String method, String path, RiskLevel risk) {
        Tool tool = new Tool();
        tool.setName(name);
        tool.setDescription(name);
        tool.setInputSchema(schemaFor(name));
        tool.setRiskLevel(risk);
        tool.setOwnerTeam("shop-team");
        tool.setKind(ToolKind.REST);
        tool.setHttpMethod(method);
        tool.setBaseUrl("http://localhost:" + shopServer.getAddress().getPort());
        tool.setPath(path);
        return tool;
    }

    private static String schemaFor(String name) {
        return switch (name) {
            case "issueRefund" -> """
                    {"type":"object","properties":{"orderId":{"type":"integer"},"amount":{"type":"number"},"reason":{"type":"string"}},"required":["orderId","amount","reason"],"additionalProperties":false}""";
            case "getCustomer" -> """
                    {"type":"object","properties":{"id":{"type":"integer"}},"required":["id"],"additionalProperties":false}""";
            default -> """
                    {"type":"object","properties":{"id":{"type":"string"}},"required":["id"],"additionalProperties":false}""";
        };
    }
}
