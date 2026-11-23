package com.agentgate.mocktools;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Fake "ShopBD" tool server. Its REST endpoints are the downstream tools that AgentGate will protect.
 * All data is in memory and resets on every restart.
 */
@SpringBootApplication
@OpenAPIDefinition(info = @Info(
        title = "ShopBD mock tools API",
        version = "1.0.0",
        description = "Fake e-commerce tools (customers, orders, refunds, SMS) used to demonstrate AgentGate."))
public class MockToolsApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockToolsApplication.class, args);
    }
}
