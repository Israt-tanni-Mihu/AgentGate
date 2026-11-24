package com.agentgate.gateway.guard;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@link GuardProperties} so the guard steps can inject it. */
@Configuration
@EnableConfigurationProperties(GuardProperties.class)
public class GuardConfig {
}
