package com.agentgate.mocktools.shop;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Body of POST /api/sms. 160 characters = one standard SMS. */
public record SmsRequest(
        @Schema(description = "Customer who receives the SMS", example = "1")
        @NotNull @Positive Long customerId,

        @Schema(description = "SMS text (max 160 characters)", example = "Your order has been shipped.")
        @NotBlank @Size(max = 160) String message) {
}
