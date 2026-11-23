package com.agentgate.mocktools.chaos;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test helper used by later FR10 (timeouts / circuit breakers) and QA scripts.
 * Not part of the OpenAPI spec (see springdoc.paths-to-match), so it never becomes a gateway tool.
 * No security here on purpose: mock-tools is a local fake downstream service, not part of the product.
 */
@RestController
@RequestMapping("/admin/chaos")
public class ChaosController {

    private final ChaosState chaosState;

    public ChaosController(ChaosState chaosState) {
        this.chaosState = chaosState;
    }

    @GetMapping
    public ChaosSettings current() {
        return chaosState.get();
    }

    @PostMapping
    public ChaosSettings update(@Valid @RequestBody ChaosSettings settings) {
        chaosState.set(settings);
        return settings;
    }
}
