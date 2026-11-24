package com.agentgate.mocktools.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo receiver for the gateway's approval webhook (FR4): it only logs what it gets.
 * Not part of the OpenAPI spec, so it never becomes a gateway tool.
 */
@RestController
@RequestMapping("/hooks")
public class WebhookReceiverController {

    private static final Logger log = LoggerFactory.getLogger(WebhookReceiverController.class);

    @PostMapping("/approvals")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void approvalRequested(@RequestBody String body) {
        log.info("WEBHOOK received: approval requested {}", body);
    }
}
