package com.agentgate.mocktools.shop;

/**
 * A ShopBD customer. Email, phone and nationalId are personal data on purpose:
 * the gateway must redact them later (FR5).
 */
public record Customer(
        long id,
        String name,
        String email,
        String phone,       // Bangladeshi mobile number, 11 digits: 01XXXXXXXXX
        String nationalId,  // Bangladeshi NID: 10, 13 or 17 digits
        String city) {
}
