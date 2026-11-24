package com.agentgate.gateway.registry.importer;

import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Downloads a spec document over HTTP(S). Kept separate so the import logic can be tested without a network. */
@Component
public class SpecDownloader {

    private final RestClient restClient;

    public SpecDownloader() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    public String download(String specUrl) {
        try {
            String body = restClient.get().uri(specUrl).retrieve().body(String.class);
            if (body == null || body.isBlank()) {
                throw new SpecImportException(HttpStatus.BAD_GATEWAY, "Spec at " + specUrl + " is empty");
            }
            return body;
        } catch (RestClientException e) {
            throw new SpecImportException(HttpStatus.BAD_GATEWAY,
                    "Could not download spec from " + specUrl + ": " + e.getMessage());
        }
    }
}
