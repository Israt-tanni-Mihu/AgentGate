package com.agentgate.gateway.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/** The webhook observer: it delivers the event, and a dead or slow receiver never blocks the approval flow. */
class ApprovalWebhookNotifierTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ApprovalRequested event = new ApprovalRequested(UUID.randomUUID(), "support-bot", "1", "issueRefund",
            mapper.createObjectNode().put("amount", 100), Instant.now().plusSeconds(60));

    private ApprovalWebhookNotifier notifierFor(String url) {
        var properties = new ApprovalProperties(Duration.ofMinutes(1), url, new ApprovalProperties.Approver("a", "b"));
        return new ApprovalWebhookNotifier(properties, RestClient.builder());
    }

    @Test
    void postsTheRequestAsJsonToTheConfiguredUrl() throws Exception {
        BlockingQueue<String> bodies = new LinkedBlockingQueue<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/hook", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        server.start();
        try {
            notifierFor("http://localhost:" + server.getAddress().getPort() + "/hook").onRequested(event);

            String body = bodies.poll(5, TimeUnit.SECONDS);
            assertThat(body).contains(event.approvalId().toString()).contains("issueRefund").contains("support-bot");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aDeadWebhookNeverThrowsAndNeverBlocksTheCaller() {
        // Nothing listens on port 1: the connection is refused.
        assertThatCode(() -> notifierFor("http://localhost:1/hook").onRequested(event)).doesNotThrowAnyException();
    }

    @Test
    void aSlowWebhookDoesNotBlockTheCaller() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/hook", exchange -> {
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException ignored) {
                // test is over
            }
            exchange.close();
        });
        server.start();
        try {
            long start = System.nanoTime();
            notifierFor("http://localhost:" + server.getAddress().getPort() + "/hook").onRequested(event);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(500));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void noUrlMeansNoWebhook() {
        assertThatCode(() -> notifierFor("").onRequested(event)).doesNotThrowAnyException();
    }
}
