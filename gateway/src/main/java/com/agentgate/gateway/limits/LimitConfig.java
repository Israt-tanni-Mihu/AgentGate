package com.agentgate.gateway.limits;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers {@link LimitProperties} and the clock that the fixed windows use (tests replace it with a fake). */
@Configuration
@EnableConfigurationProperties(LimitProperties.class)
public class LimitConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
