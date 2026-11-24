package com.agentgate.gateway.health;

import com.agentgate.gateway.pipeline.CallStep;
import com.agentgate.gateway.pipeline.CallStepOrder;
import com.agentgate.gateway.pipeline.ForwardStep;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/** Registers {@link ResilienceProperties} and puts the circuit breaker around the real {@link ForwardStep}. */
@Configuration
@EnableConfigurationProperties(ResilienceProperties.class)
public class HealthConfig {

    /** The last step of the pipeline: ForwardStep inside the breaker/time-limit decorator. */
    @Bean
    @Order(CallStepOrder.FORWARD)
    CallStep forwardStep(RestClient.Builder builder, ObjectMapper objectMapper, ToolBreakers breakers) {
        return new ResilientForwardStep(new ForwardStep(builder, objectMapper), breakers);
    }
}
