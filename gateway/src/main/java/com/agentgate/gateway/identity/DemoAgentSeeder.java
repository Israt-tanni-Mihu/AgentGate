package com.agentgate.gateway.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * "local" profile only: creates two demo agents at startup if they do not exist yet.
 * Existing agents are left alone (so a secret change in env vars does not overwrite a stored agent).
 */
@Component
@Profile("local")
@EnableConfigurationProperties(DemoAgentProperties.class)
class DemoAgentSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoAgentSeeder.class);

    private final AgentService agentService;
    private final DemoAgentProperties properties;

    DemoAgentSeeder(AgentService agentService, DemoAgentProperties properties) {
        this.agentService = agentService;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed(new RegisterAgentRequest("support-bot", "ShopBD customer support bot", Clearance.LOW),
                properties.supportBotSecret());
        seed(new RegisterAgentRequest("analytics-bot", "ShopBD analytics bot", Clearance.HIGH),
                properties.analyticsBotSecret());
    }

    private void seed(RegisterAgentRequest request, String secret) {
        if (agentService.exists(request.clientId())) {
            return;
        }
        agentService.registerWithSecret(request, secret);
        log.info("Seeded demo agent '{}' with {} clearance", request.clientId(), request.clearance());
    }
}
