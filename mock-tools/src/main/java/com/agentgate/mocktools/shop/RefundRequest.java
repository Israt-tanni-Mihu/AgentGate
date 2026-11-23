package com.agentgate.mocktools.shop;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** Body of POST /api/refunds. The constraints also end up in the OpenAPI JSON schema (used by FR1/FR5 later). */
public record RefundRequest(
        @Schema(description = "Order to refund", example = "1002")
        @NotNull @Positive Long orderId,

        @Schema(description = "Refund amount in BDT", example = "500.00")
        @NotNull @DecimalMin("0.01") @Digits(integer = 9, fraction = 2) BigDecimal amount,

        @Schema(description = "Why the refund is issued", example = "Item arrived damaged")
        @NotBlank @Size(max = 200) String reason) {
}
