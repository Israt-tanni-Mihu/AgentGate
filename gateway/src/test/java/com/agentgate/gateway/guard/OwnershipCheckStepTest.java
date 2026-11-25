package com.agentgate.gateway.guard;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentgate.gateway.identity.CallContext;
import com.agentgate.gateway.identity.Clearance;
import com.agentgate.gateway.pipeline.CallChain;
import com.agentgate.gateway.pipeline.CallOutcome;
import com.agentgate.gateway.pipeline.ToolCall;
import com.agentgate.gateway.registry.AccessMode;
import com.agentgate.gateway.registry.RiskLevel;
import com.agentgate.gateway.registry.ToolKind;
import com.agentgate.gateway.registry.ToolResponse;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** QA1 findings: the ownership guard covers customer profiles too, for every agent, and only when an end user is named. */
class OwnershipCheckStepTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final OwnershipCheckStep step = new OwnershipCheckStep();

    @Test
    void customerProfileOfAnotherCustomerIsWithheldEvenForAnAgentWithABroadReadPolicy() {
        CallOutcome outcome = run("analytics-bot", "1", "getCustomer", "{\"id\": 2, \"name\": \"Fatema\"}");

        assertThat(outcome).isInstanceOfSatisfying(CallOutcome.Denied.class,
                denied -> assertThat(denied.rule()).isEqualTo("ownership-check"));
    }

    @Test
    void ownCustomerProfileIsAllowed() {
        assertThat(run("analytics-bot", "1", "getCustomer", "{\"id\": 1, \"name\": \"Rahim\"}"))
                .isInstanceOf(CallOutcome.Completed.class);
    }

    @Test
    void orderOfAnotherCustomerIsStillWithheld() {
        assertThat(run("support-bot", "1", "getOrder", "{\"id\": 1003, \"customerId\": 2}"))
                .isInstanceOf(CallOutcome.Denied.class);
        assertThat(run("support-bot", "1", "getOrder", "{\"id\": 1001, \"customerId\": 1}"))
                .isInstanceOf(CallOutcome.Completed.class);
    }

    @Test
    void agentsActingForThemselvesAreNotChecked() {
        assertThat(run("analytics-bot", null, "getCustomer", "{\"id\": 2}")).isInstanceOf(CallOutcome.Completed.class);
    }

    private CallOutcome run(String agent, String endUser, String tool, String resultJson) {
        ToolResponse response = new ToolResponse(1L, tool, "test tool", null, RiskLevel.LOW, "shop-team",
                ToolKind.REST, "GET", "http://localhost:8081", "/api/test", true,
                AccessMode.defaultFor(ToolKind.REST, "GET"), null, Instant.EPOCH, Instant.EPOCH);
        ToolCall call = new ToolCall(response, JSON.createObjectNode(), new CallContext(agent, endUser, Clearance.HIGH));
        CallChain downstream = ignored -> new CallOutcome.Completed(200, JSON.readTree(resultJson));
        return step.handle(call, downstream);
    }
}
