package com.agentgate.gateway.policy;

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

/** Pipeline step for FR3: asks the PolicyEngine and either passes the call on or stops it. */
@Component
@Order(CallStepOrder.POLICY)
public class PolicyStep implements CallStep {

    private static final Logger log = LoggerFactory.getLogger(PolicyStep.class);

    private final PolicyEngine engine;
    private final ActivePolicyCache cache;

    public PolicyStep(PolicyEngine engine, ActivePolicyCache cache) {
        this.engine = engine;
        this.cache = cache;
    }

    @Override
    public CallOutcome handle(ToolCall call, CallChain chain) {
        PolicySnapshot policy = cache.current();
        PolicyDecision decision = engine.evaluate(policy, PolicyInput.from(call));

        if (decision.allowed()) {
            log.debug("ALLOW agent={} tool={} rule={} policyVersion={}",
                    call.context().agentId(), call.toolName(), decision.ruleId(), decision.policyVersion());
            return chain.proceed(call);
        }
        if (policy.dryRun()) {
            // Dry-run: only report what WOULD have been blocked, then let the call through.
            log.warn("DRY-RUN would DENY agent={} endUser={} tool={} rule={} policyVersion={} reason={}",
                    call.context().agentId(), call.context().endUserId(), call.toolName(),
                    decision.ruleId(), decision.policyVersion(), decision.reason());
            return chain.proceed(call);
        }
        log.info("DENY agent={} endUser={} tool={} rule={} policyVersion={}",
                call.context().agentId(), call.context().endUserId(), call.toolName(),
                decision.ruleId(), decision.policyVersion());
        return new CallOutcome.Denied(decision.ruleId(), decision.reason(),
                Map.of("policyVersion", decision.policyVersion()));
    }
}
