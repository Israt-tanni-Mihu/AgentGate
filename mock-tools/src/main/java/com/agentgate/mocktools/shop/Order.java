package com.agentgate.mocktools.shop;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** A ShopBD order. Amounts are in BDT. BigDecimal is used because money must not use floating point. */
public record Order(
        long id,
        long customerId,
        List<String> items,
        BigDecimal total,
        String currency,
        OrderStatus status,
        LocalDate placedOn,
        String deliveryPhone) {

    public enum OrderStatus { PROCESSING, SHIPPED, DELIVERED, CANCELLED }
}
