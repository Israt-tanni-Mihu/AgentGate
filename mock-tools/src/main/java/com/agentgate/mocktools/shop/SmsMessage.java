package com.agentgate.mocktools.shop;

import java.time.Instant;

/** Result of a (fake) sent SMS. Contains the phone number, so the gateway has something to redact (FR5). */
public record SmsMessage(
        String smsId,
        long customerId,
        String to,
        String message,
        String status,
        Instant sentAt) {
}
