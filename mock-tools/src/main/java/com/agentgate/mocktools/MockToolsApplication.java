package com.agentgate.mocktools;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Fake "ShopBD" tool server. The tool endpoints (getOrder, issueRefund, ...) come in a later task. */
@SpringBootApplication
public class MockToolsApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockToolsApplication.class, args);
    }
}
