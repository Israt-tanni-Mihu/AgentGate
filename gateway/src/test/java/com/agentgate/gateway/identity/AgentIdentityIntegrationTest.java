package com.agentgate.gateway.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;

import com.agentgate.gateway.TestcontainersConfiguration;
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
 * FR2 end to end: real Spring Authorization Server + resource server + PostgreSQL (Testcontainers).
 * Runs with the default "local" profile, so the demo agents support-bot and analytics-bot exist,
 * and the admin login is admin / admin-local-only.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AgentIdentityIntegrationTest {

    static final String ADMIN_USER = "admin";
    static final String ADMIN_PASSWORD = "admin-local-only";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ObjectMapper objectMapper;

    // ---------- getting a token ----------

    @Test
    void clientCredentialsGivesBearerJwt() {
        MvcTestResult result = requestToken("support-bot", "support-bot-local-secret");

        assertThat(result).hasStatusOk();
        JsonNode body = json(result);
        assertThat(body.get("token_type").asString()).isEqualTo("Bearer");
        assertThat(body.get("expires_in").asInt()).isBetween(290, 300);   // 5 minutes
        assertThat(body.get("access_token").asString().split("\\.")).hasSize(3);   // header.payload.signature = a JWT
    }

    @Test
    void wrongSecretIsRejected() {
        MvcTestResult result = requestToken("support-bot", "not-the-secret");

        assertThat(result).hasStatus(401);
        assertThat(json(result).get("error").asString()).isEqualTo("invalid_client");
    }

    @Test
    void unknownAgentIsRejected() {
        assertThat(requestToken("nobody-bot", "whatever")).hasStatus(401);
    }

    // ---------- whoami ----------

    @Test
    void whoAmIShowsAgentEndUserAndClearance() {
        String token = tokenFor("support-bot", "support-bot-local-secret");

        assertThat(mvc.get().uri("/api/agent/whoami")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-On-Behalf-Of", "cust-1001"))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.agentId", v -> v.assertThat().isEqualTo("support-bot"))
                .hasPathSatisfying("$.endUserId", v -> v.assertThat().isEqualTo("cust-1001"))
                .hasPathSatisfying("$.clearance", v -> v.assertThat().isEqualTo("LOW"));
    }

    @Test
    void whoAmIWithoutEndUserHeaderHasNullEndUser() {
        String token = tokenFor("analytics-bot", "analytics-bot-local-secret");

        assertThat(mvc.get().uri("/api/agent/whoami").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.agentId", v -> v.assertThat().isEqualTo("analytics-bot"))
                .hasPathSatisfying("$.endUserId", v -> v.assertThat().isNull())
                .hasPathSatisfying("$.clearance", v -> v.assertThat().isEqualTo("HIGH"));
    }

    @Test
    void whoAmIWithoutTokenIs401ProblemDetails() {
        MvcTestResult result = mvc.get().uri("/api/agent/whoami").exchange();

        assertThat(result).hasStatus(401).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
    }

    @Test
    void whoAmIWithForgedTokenIs401() {
        String forged = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJzdXBwb3J0LWJvdCJ9.c2lnbmF0dXJl";

        assertThat(mvc.get().uri("/api/agent/whoami").header(HttpHeaders.AUTHORIZATION, "Bearer " + forged))
                .hasStatus(401);
    }

    @Test
    void smuggledEndUserHeaderIs400() {
        String token = tokenFor("support-bot", "support-bot-local-secret");

        assertThat(mvc.get().uri("/api/agent/whoami")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-On-Behalf-Of", "cust-1001, cust-2002"))
                .hasStatus(400)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    }

    // ---------- admin API + disabling ----------

    @Test
    void registeredAgentCanLogInUntilDisabled() {
        MvcTestResult created = mvc.post().uri("/api/admin/agents").with(httpBasic(ADMIN_USER, ADMIN_PASSWORD))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"clientId": "refund-bot", "displayName": "Refund bot", "clearance": "LOW"}
                        """)
                .exchange();
        assertThat(created).hasStatus(201);
        String secret = json(created).get("clientSecret").asString();
        assertThat(secret).hasSizeGreaterThanOrEqualTo(40);

        // The secret is shown once: GET and list never contain it.
        assertThat(mvc.get().uri("/api/admin/agents/refund-bot").with(httpBasic(ADMIN_USER, ADMIN_PASSWORD)))
                .hasStatusOk()
                .bodyJson()
                .doesNotHavePath("$.clientSecret")
                .doesNotHavePath("$.clientSecretHash");

        String token = tokenFor("refund-bot", secret);
        assertThat(mvc.get().uri("/api/agent/whoami").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .hasStatusOk();

        assertThat(mvc.post().uri("/api/admin/agents/refund-bot/disable").with(httpBasic(ADMIN_USER, ADMIN_PASSWORD)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.enabled", v -> v.assertThat().isEqualTo(false));

        // Disabled: no new tokens, and the token it already holds stops working at once.
        assertThat(requestToken("refund-bot", secret)).hasStatus(401);
        assertThat(mvc.get().uri("/api/agent/whoami").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .hasStatus(401);
    }

    @Test
    void listAgentsIsPagedAndHidesSecrets() {
        assertThat(mvc.get().uri("/api/admin/agents?size=1").with(httpBasic(ADMIN_USER, ADMIN_PASSWORD)))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.content.length()", v -> v.assertThat().isEqualTo(1))
                .hasPath("$.page.totalElements")
                .doesNotHavePath("$.content[0].clientSecret");
    }

    @Test
    void adminEndpointsRejectMissingOrWrongLogin() {
        MvcTestResult noLogin = mvc.get().uri("/api/admin/agents").exchange();
        assertThat(noLogin).hasStatus(401).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(noLogin.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Basic");

        assertThat(mvc.get().uri("/api/admin/tools").with(httpBasic(ADMIN_USER, "wrong"))).hasStatus(401);
        assertThat(mvc.post().uri("/api/admin/agents").contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientId\":\"evil-bot\",\"displayName\":\"x\",\"clearance\":\"HIGH\"}"))
                .hasStatus(401);
    }

    @Test
    void agentTokenDoesNotOpenAdminEndpoints() {
        String token = tokenFor("analytics-bot", "analytics-bot-local-secret");

        assertThat(mvc.get().uri("/api/admin/agents").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .hasStatus(401);
    }

    @Test
    void onlyHealthAndApiDocsAreOpen() {
        assertThat(mvc.get().uri("/actuator/health")).hasStatusOk();
        assertThat(mvc.get().uri("/v3/api-docs")).hasStatusOk();
        assertThat(mvc.get().uri("/actuator/info")).hasStatus(401);
        assertThat(mvc.get().uri("/api/calls/anything")).hasStatus(401);
    }

    // ---------- helpers ----------

    private MvcTestResult requestToken(String clientId, String secret) {
        return mvc.post().uri("/oauth2/token")
                .with(httpBasic(clientId, secret))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "client_credentials")
                .exchange();
    }

    private String tokenFor(String clientId, String secret) {
        MvcTestResult result = requestToken(clientId, secret);
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
}
