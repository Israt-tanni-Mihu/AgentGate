package com.agentgate.gateway.guard;

import com.agentgate.gateway.pipeline.CallChain;
import com.agentgate.gateway.pipeline.CallOutcome;
import com.agentgate.gateway.pipeline.CallStep;
import com.agentgate.gateway.pipeline.CallStepOrder;
import com.agentgate.gateway.pipeline.ToolCall;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * FR5 guard, runs AFTER the tool answered. The policy cannot see which customer owns an order (only the
 * result says so), so this step checks the result: when an end user is present, the order (or customer profile)
 * must belong to that end user. Otherwise the result is replaced by a 403, and the data never reaches the agent.
 * It applies to every agent, whatever its policy says (QA1 finding: a broad "read" rule of another agent must
 * not let it read other customers' profiles while acting for one customer).
 *
 * This step is the innermost result guard, so it sees the full, un-redacted result.
 */
@Component
@Order(CallStepOrder.OWNERSHIP)
public class OwnershipCheckStep implements CallStep {

    private static final Logger log = LoggerFactory.getLogger(OwnershipCheckStep.class);
    /** Customer data tools and the result field that names the customer the data belongs to. */
    private static final Map<String, String> OWNER_FIELD_OF_TOOL = Map.of("getOrder", "customerId", "getCustomer", "id");

    @Override
    public CallOutcome handle(ToolCall call, CallChain chain) {
        CallOutcome outcome = chain.proceed(call);
        if (!(outcome instanceof CallOutcome.Completed completed)
                || !OWNER_FIELD_OF_TOOL.containsKey(call.toolName())
                || !call.context().hasEndUser()
                || !isSuccess(completed.status())) {
            return outcome;
        }

        String endUser = call.context().endUserId();
        // A missing owner field means "not proven to be the end user's order", so block (fail closed).
        String owner = completed.body() == null ? "" : completed.body().path(OWNER_FIELD_OF_TOOL.get(call.toolName())).asString();
        if (owner.equals(endUser)) {
            return outcome;
        }
        log.warn("BLOCKED cross-customer read agent={} endUser={} tool={} ownerInResult={}",
                call.context().agentId(), endUser, call.toolName(), owner);
        return new CallOutcome.Denied("ownership-check",
                "cross-customer read blocked: the data does not belong to the end user", Map.of());
    }

    private static boolean isSuccess(int status) {
        return status >= 200 && status < 300;
    }
}
