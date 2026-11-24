package com.agentgate.gateway.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentgate.gateway.policy.PolicyRule.Effect;
import com.agentgate.gateway.policy.operator.EqualsOperator;
import com.agentgate.gateway.policy.operator.GreaterThanOrEqualOperator;
import com.agentgate.gateway.policy.operator.InOperator;
import com.agentgate.gateway.policy.operator.LessThanOrEqualOperator;
import com.agentgate.gateway.policy.operator.MatchesEndUserOperator;
import com.agentgate.gateway.policy.operator.NotEqualsOperator;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/** Plain unit tests of the decision logic and the operators: no Spring, no database. */
class PolicyEngineTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    PolicyEngine engine = new PolicyEngine(List.of(new EqualsOperator(), new NotEqualsOperator(),
            new LessThanOrEqualOperator(), new GreaterThanOrEqualOperator(), new InOperator(),
            new MatchesEndUserOperator()));

    static final PolicyRule REFUND_UP_TO_2000 = new PolicyRule("refund-max", null, Effect.ALLOW,
            List.of("support-bot"), List.of("issueRefund"),
            List.of(new Condition("amount", "lessThanOrEqual", NODES.numberNode(2000))));

    static final PolicyRule OWN_CUSTOMER = new PolicyRule("own-customer", null, Effect.ALLOW,
            List.of("support-bot"), List.of("getCustomer"), List.of(new Condition("id", "matchesEndUser", null)));

    @Test
    void noRulesMeansDefaultDeny() {
        PolicyDecision decision = decide(List.of(), "support-bot", null, "getOrder", "{\"id\": 1}");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.ruleId()).isEqualTo("default-deny");
    }

    @Test
    void conditionDecidesAndTheRuleIsNamed() {
        PolicyDecision small = decide(List.of(REFUND_UP_TO_2000), "support-bot", null, "issueRefund", "{\"amount\": 1500}");
        PolicyDecision exact = decide(List.of(REFUND_UP_TO_2000), "support-bot", null, "issueRefund", "{\"amount\": 2000.00}");
        PolicyDecision big = decide(List.of(REFUND_UP_TO_2000), "support-bot", null, "issueRefund", "{\"amount\": 5000}");

        assertThat(small.allowed()).isTrue();
        assertThat(small.ruleId()).isEqualTo("refund-max");
        assertThat(exact.allowed()).isTrue();
        assertThat(big.allowed()).isFalse();
        assertThat(big.ruleId()).isEqualTo("default-deny");
        assertThat(big.reason()).contains("refund-max").contains("5000");
    }

    @Test
    void numbersSentAsTextAreStillCompared() {
        // Argument smuggling: "5000" as a string must not slip past "amount <= 2000".
        assertThat(decide(List.of(REFUND_UP_TO_2000), "support-bot", null, "issueRefund", "{\"amount\": \"5000\"}")
                .allowed()).isFalse();
        assertThat(decide(List.of(REFUND_UP_TO_2000), "support-bot", null, "issueRefund", "{\"amount\": \"abc\"}")
                .allowed()).isFalse();
        assertThat(decide(List.of(REFUND_UP_TO_2000), "support-bot", null, "issueRefund", "{}")
                .allowed()).isFalse();
    }

    @Test
    void ruleOnlyAppliesToItsAgentsAndTools() {
        assertThat(decide(List.of(REFUND_UP_TO_2000), "analytics-bot", null, "issueRefund", "{\"amount\": 10}")
                .allowed()).isFalse();
        assertThat(decide(List.of(REFUND_UP_TO_2000), "support-bot", null, "sendSms", "{\"amount\": 10}")
                .allowed()).isFalse();
    }

    @Test
    void matchesEndUserBlocksCrossCustomerReads() {
        assertThat(decide(List.of(OWN_CUSTOMER), "support-bot", "1", "getCustomer", "{\"id\": 1}").allowed()).isTrue();
        assertThat(decide(List.of(OWN_CUSTOMER), "support-bot", "1", "getCustomer", "{\"id\": 2}").allowed()).isFalse();
        assertThat(decide(List.of(OWN_CUSTOMER), "support-bot", null, "getCustomer", "{\"id\": 1}").allowed()).isFalse();
    }

    @Test
    void denyBeatsAllowWhateverTheOrder() {
        PolicyRule allowAll = new PolicyRule("allow-all", null, Effect.ALLOW, List.of("*"), List.of("*"), List.of());
        PolicyRule denyCancelled = new PolicyRule("no-cancelled", "Never touch cancelled orders", Effect.DENY,
                List.of("*"), List.of("*"), List.of(new Condition("status", "equals", NODES.stringNode("CANCELLED"))));

        PolicyDecision decision = decide(List.of(allowAll, denyCancelled), "support-bot", null, "getOrder",
                "{\"status\": \"CANCELLED\"}");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.ruleId()).isEqualTo("no-cancelled");
        assertThat(decide(List.of(allowAll, denyCancelled), "support-bot", null, "getOrder", "{\"status\": \"SHIPPED\"}")
                .ruleId()).isEqualTo("allow-all");
    }

    @Test
    void otherOperators() {
        PolicyRule rule = new PolicyRule("mixed", null, Effect.ALLOW, List.of("*"), List.of("*"), List.of(
                new Condition("amount", "greaterThanOrEqual", NODES.numberNode(10)),
                new Condition("city", "in", JSON.readTree("[\"Dhaka\", \"Sylhet\"]")),
                new Condition("channel", "notEquals", NODES.stringNode("sms"))));

        assertThat(decide(List.of(rule), "a", null, "t", "{\"amount\": 10, \"city\": \"Dhaka\", \"channel\": \"email\"}")
                .allowed()).isTrue();
        assertThat(decide(List.of(rule), "a", null, "t", "{\"amount\": 9, \"city\": \"Dhaka\", \"channel\": \"email\"}")
                .allowed()).isFalse();
        assertThat(decide(List.of(rule), "a", null, "t", "{\"amount\": 10, \"city\": \"Khulna\", \"channel\": \"email\"}")
                .allowed()).isFalse();
        // notEquals with a MISSING argument must not count as "different" (fail closed)
        assertThat(decide(List.of(rule), "a", null, "t", "{\"amount\": 10, \"city\": \"Dhaka\"}")
                .allowed()).isFalse();
    }

    @Test
    void explainRecordsWhatHappenedToEveryRuleAndAgreesWithEvaluate() {
        PolicyRule denySmallRefund = new PolicyRule("no-tiny-refunds", null, Effect.DENY, List.of("*"),
                List.of("issueRefund"), List.of(new Condition("amount", "lessThanOrEqual", NODES.numberNode(10))));
        List<PolicyRule> rules = List.of(OWN_CUSTOMER, REFUND_UP_TO_2000, denySmallRefund, REFUND_UP_TO_2000_COPY);
        PolicySnapshot policy = new PolicySnapshot(7, false, rules);

        PolicyInput big = new PolicyInput("support-bot", null, "issueRefund", JSON.readTree("{\"amount\": 5000}"));
        PolicyEvaluation bigResult = engine.explain(policy, big);
        assertThat(bigResult.decision()).isEqualTo(engine.evaluate(policy, big));
        assertThat(bigResult.checks()).extracting(PolicyEvaluation.RuleCheck::result).containsExactly(
                PolicyEvaluation.Result.NOT_FOR_THIS_CALL,   // own-customer is for getCustomer
                PolicyEvaluation.Result.CONDITION_FAILED,    // 5000 > 2000
                PolicyEvaluation.Result.CONDITION_FAILED,    // 5000 > 10, so the DENY rule does not match
                PolicyEvaluation.Result.CONDITION_FAILED);
        assertThat(bigResult.checks().get(1).detail()).contains("5000");

        PolicyInput tiny = new PolicyInput("support-bot", null, "issueRefund", JSON.readTree("{\"amount\": 5}"));
        PolicyEvaluation tinyResult = engine.explain(policy, tiny);
        assertThat(tinyResult.decision()).isEqualTo(engine.evaluate(policy, tiny));
        assertThat(tinyResult.decision().ruleId()).isEqualTo("no-tiny-refunds");
        assertThat(tinyResult.checks()).extracting(PolicyEvaluation.RuleCheck::result).containsExactly(
                PolicyEvaluation.Result.NOT_FOR_THIS_CALL,
                PolicyEvaluation.Result.MATCHED,
                PolicyEvaluation.Result.MATCHED,
                PolicyEvaluation.Result.NOT_CHECKED);        // the DENY already decided
    }

    static final PolicyRule REFUND_UP_TO_2000_COPY = new PolicyRule("refund-max-copy", null, Effect.ALLOW,
            List.of("support-bot"), List.of("issueRefund"),
            List.of(new Condition("amount", "lessThanOrEqual", NODES.numberNode(2000))));

    private PolicyDecision decide(List<PolicyRule> rules, String agent, String endUser, String tool, String args) {
        JsonNode arguments = JSON.readTree(args);
        return engine.evaluate(new PolicySnapshot(7, false, rules), new PolicyInput(agent, endUser, tool, arguments));
    }
}
