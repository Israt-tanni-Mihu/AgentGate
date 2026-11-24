package com.agentgate.gateway.shared;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on @Scheduled methods (e.g. the policy cache refresh). */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
