package com.agentgate.gateway.approval;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * OBSERVER of {@link ApprovalRequested}: POSTs the request to the configured webhook URL.
 * Runs after the commit, on its own virtual thread, and swallows every error: a dead webhook must never
 * block or break the approval flow (the web page and the API still work).
 * (The web page is the other observer: it simply reads the pending requests from the database.)
 */
@Component
class ApprovalWebhookNotifier {

    private static final Logger log = LoggerFactory.getLogger(ApprovalWebhookNotifier.class);

    private final ApprovalProperties properties;
    private final RestClient restClient;

    ApprovalWebhookNotifier(ApprovalProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = builder.requestFactory(factory).build();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onRequested(ApprovalRequested event) {
        String url = properties.webhookUrl();
        if (!StringUtils.hasText(url)) {
            return;
        }
        Thread.startVirtualThread(() -> {
            try {
                restClient.post().uri(url).contentType(MediaType.APPLICATION_JSON).body(event)
                        .retrieve().toBodilessEntity();
            } catch (Exception e) {
                log.warn("Approval webhook failed for {} (ignored): {}", event.approvalId(), e.toString());
            }
        });
    }
}
