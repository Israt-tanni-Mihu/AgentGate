package com.agentgate.gateway.policy;

import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Converts the rules between Java objects and the JSON stored in policy_versions.rules. */
@Component
class PolicyRulesJson {

    private static final TypeReference<List<PolicyRule>> RULE_LIST = new TypeReference<>() { };

    private final ObjectMapper objectMapper;

    PolicyRulesJson(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String write(List<PolicyRule> rules) {
        return objectMapper.writeValueAsString(rules);
    }

    List<PolicyRule> read(String json) {
        return objectMapper.readValue(json, RULE_LIST);
    }

    PolicySnapshot toSnapshot(PolicyVersion version) {
        return new PolicySnapshot(version.getVersion(), version.isDryRun(), read(version.getRules()));
    }
}
