package com.agentgate.mocktools.shop;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The four ShopBD "tools". Each operationId is the tool name the gateway will use after the
 * OpenAPI import (FR1). The x-risk-level extension is only a hint for the importer; the admin
 * still decides the final risk level in the gateway.
 */
@RestController
@Tag(name = "ShopBD tools")
public class ShopToolsController {

    private final ShopStore store;

    public ShopToolsController(ShopStore store) {
        this.store = store;
    }

    @GetMapping("/api/customers/{id}")
    @Operation(operationId = "getCustomer",
            summary = "Get a customer by id",
            description = "Read-only. Returns the customer's profile including name, email, phone number "
                    + "and national ID number (personal data).",
            extensions = @Extension(properties = @ExtensionProperty(name = "x-risk-level", value = "low")))
    public Customer getCustomer(@PathVariable @Positive long id) {
        return store.getCustomer(id);
    }

    @GetMapping("/api/orders/{id}")
    @Operation(operationId = "getOrder",
            summary = "Get an order by id",
            description = "Read-only. Returns the order's items, total in BDT, status and the customer it belongs to.",
            extensions = @Extension(properties = @ExtensionProperty(name = "x-risk-level", value = "low")))
    public Order getOrder(@PathVariable @Positive long id) {
        return store.getOrder(id);
    }

    @PostMapping("/api/refunds")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "issueRefund",
            summary = "Issue a refund for an order",
            description = "Write. Moves real money back to the customer. The total refunded for an order can "
                    + "never exceed the order total. Cancelled orders cannot be refunded.",
            extensions = @Extension(properties = @ExtensionProperty(name = "x-risk-level", value = "high")))
    public Refund issueRefund(@Valid @RequestBody RefundRequest request) {
        return store.issueRefund(request);
    }

    @PostMapping("/api/sms")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "sendSms",
            summary = "Send an SMS to a customer",
            description = "Write. Sends a text message (max 160 characters) to the customer's registered phone number.",
            extensions = @Extension(properties = @ExtensionProperty(name = "x-risk-level", value = "medium")))
    public SmsMessage sendSms(@Valid @RequestBody SmsRequest request) {
        return store.sendSms(request);
    }
}
