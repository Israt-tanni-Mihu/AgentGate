package com.agentgate.mocktools.shop;

import java.math.BigDecimal;
import java.time.Instant;

/** Result of a successful refund. */
public record Refund(
        String refundId,
        long orderId,
        BigDecimal amount,
        String currency,
        String reason,
        String status,
        Instant issuedAt) {
}
