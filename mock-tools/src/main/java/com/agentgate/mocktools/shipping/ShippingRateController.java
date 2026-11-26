package com.agentgate.mocktools.shipping;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * QA5 (adoption): a "new" REST tool that the gateway does NOT seed. A classmate imports it by hand from its
 * own OpenAPI file (static/openapi/shipping-rate.json). It lives outside /api/** on purpose, so it never
 * appears in /v3/api-docs (that spec is auto-imported on a fresh start).
 */
@RestController
public class ShippingRateController {

    private static final BigDecimal INSIDE_DHAKA_BASE = new BigDecimal("60");
    private static final BigDecimal OUTSIDE_DHAKA_BASE = new BigDecimal("120");
    private static final BigDecimal PER_EXTRA_KG = new BigDecimal("20");

    public record ShippingRate(double weightKg, String zone, BigDecimal rate, String currency) {
    }

    @GetMapping("/shipping/rate")
    public ShippingRate rate(
            @RequestParam @Positive @DecimalMax("100") double weightKg,
            @RequestParam @Pattern(regexp = "inside_dhaka|outside_dhaka") String zone) {
        BigDecimal base = zone.equals("inside_dhaka") ? INSIDE_DHAKA_BASE : OUTSIDE_DHAKA_BASE;
        // First kg is in the base price; every started kg after that costs extra.
        BigDecimal extraKg = BigDecimal.valueOf(Math.max(0, weightKg - 1)).setScale(0, RoundingMode.CEILING);
        return new ShippingRate(weightKg, zone, base.add(PER_EXTRA_KG.multiply(extraKg)), "BDT");
    }
}
