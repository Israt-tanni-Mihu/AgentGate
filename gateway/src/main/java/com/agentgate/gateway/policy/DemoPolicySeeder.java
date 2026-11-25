package com.agentgate.gateway.policy;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * "local" and "docker" profiles: if there are no policy versions yet, creates version 1 with the demo rules
 * (see docs/policies.md) and activates it. sendSms has no rule on purpose: it falls to default deny.
 *
 * Convention: for ShopBD, X-On-Behalf-Of carries the CUSTOMER ID, e.g. "1" = customer 1.
 */
@Component
@Profile({"local", "docker"})
class DemoPolicySeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoPolicySeeder.class);
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final PolicyAdminService service;
    private final PolicyVersionRepository repository;

    DemoPolicySeeder(PolicyAdminService service, PolicyVersionRepository repository) {
        this.service = service;
        this.repository = repository;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (repository.count() > 0) {
            return;
        }
        List<PolicyRule> rules = List.of(
                new PolicyRule("support-own-customer-only",
                        "Support bot may read only the profile of the customer it is helping",
                        PolicyRule.Effect.ALLOW, List.of("support-bot"), List.of("getCustomer"),
                        List.of(new Condition("id", "matchesEndUser", null))),
                new PolicyRule("support-read-orders",
                        "Support bot may read orders, but only while acting for a customer (the ownership guard then checks the owner)",
                        PolicyRule.Effect.ALLOW, List.of("support-bot"), List.of("getOrder"),
                        List.of(new Condition("id", "hasEndUser", null))),
                new PolicyRule("support-refund-up-to-2000",
                        "Support bot may refund at most 2000 BDT",
                        PolicyRule.Effect.ALLOW, List.of("support-bot"), List.of("issueRefund"),
                        List.of(new Condition("amount", "lessThanOrEqual", JSON.numberNode(2000)))),
                new PolicyRule("support-track-parcel",
                        "Support bot may track parcels (tool of the downstream MCP server shop-parcels)",
                        PolicyRule.Effect.ALLOW, List.of("support-bot"), List.of("track_parcel"), List.of()),
                new PolicyRule("analytics-read-only",
                        "Analytics bot may read orders and customers",
                        PolicyRule.Effect.ALLOW, List.of("analytics-bot"), List.of("getOrder", "getCustomer"),
                        List.of()));

        PolicyVersionResponse draft = service.createDraft(
                new CreatePolicyVersionRequest("Demo policies for ShopBD", false, rules));
        service.activate(draft.version());
        log.info("Seeded and activated demo policy version {} ({} rules)", draft.version(), rules.size());
    }
}
