package com.agentgate.agentsim.qa1;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Plain HTTP helper for the QA1 runner: agent calls (REST and MCP) plus the admin and approver APIs. */
final class GatewayApi {

    /** HTTP status, body text and the X-Call-Id header (null when absent). */
    record Reply(int status, String body, String callId) {
        JsonNode json(ObjectMapper mapper) {
            try {
                return mapper.readTree(body);
            } catch (RuntimeException e) {
                return mapper.createObjectNode();
            }
        }
    }

    static final String CALL_ID_HEADER = "X-Call-Id";

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final String base;
    private final String adminAuth;
    private final String approverAuth;

    GatewayApi(String base, String adminUser, String adminPassword, String approverUser, String approverPassword) {
        this.base = base;
        this.adminAuth = basic(adminUser, adminPassword);
        this.approverAuth = basic(approverUser, approverPassword);
    }

    static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    /** OAuth2 client credentials: the only way an agent gets a token. */
    String fetchToken(String clientId, String secret, ObjectMapper mapper) {
        Reply reply = send("POST", "/oauth2/token", basic(clientId, secret), Map.of(),
                "grant_type=client_credentials", "application/x-www-form-urlencoded", Duration.ofSeconds(20));
        if (reply.status() != 200) {
            throw new IllegalStateException("No token for " + clientId + ": HTTP " + reply.status() + " " + reply.body());
        }
        return reply.json(mapper).path("access_token").asString();
    }

    Reply admin(String method, String path, String jsonBody) {
        return send(method, path, adminAuth, Map.of(), jsonBody, "application/json", Duration.ofSeconds(30));
    }

    Reply approver(String method, String path, String jsonBody) {
        return send(method, path, approverAuth, Map.of(), jsonBody, "application/json", Duration.ofSeconds(30));
    }

    /** An agent call. {@code authorization} may be null (= no credentials at all). */
    CompletableFuture<Reply> agentCallAsync(String path, String authorization, Map<String, String> headers,
                                            String jsonBody) {
        return sendAsync("POST", path, authorization, headers, jsonBody, "application/json", Duration.ofSeconds(90));
    }

    Reply send(String method, String path, String authorization, Map<String, String> headers, String body,
               String contentType, Duration timeout) {
        return sendAsync(method, path, authorization, headers, body, contentType, timeout).join();
    }

    private CompletableFuture<Reply> sendAsync(String method, String path, String authorization,
                                               Map<String, String> headers, String body, String contentType,
                                               Duration timeout) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).timeout(timeout)
                .header("Content-Type", contentType).header("Accept", "application/json, text/event-stream");
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        headers.forEach(request::header);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return http.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> new Reply(response.statusCode(), response.body(),
                        response.headers().firstValue(CALL_ID_HEADER).orElse(null)));
    }
}
