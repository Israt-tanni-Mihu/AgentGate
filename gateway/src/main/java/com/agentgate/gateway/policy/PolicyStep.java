package com.agentgate.gateway.policy;

import com.agentgate.gateway.pipeline.CallChain;
import com.agentgate.gateway.pipeline.CallOutcome;
import com.agentgate.gateway.pipeline.CallStep;
import com.agentgate.gateway.pipeline.CallStepOrder;
import com.agentgate.gateway.pipeline.CallTrace;
import com.agentgate.gateway.pipeline.ToolCall;
import com.agentgate.gateway.registry.RiskLevel;
import com.agentgate.gateway.shared.failsafe.PolicyStoreState;
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
    private final PolicyProperties properties;

    public PolicyStep(PolicyEngine engine, ActivePolicyCache cache, PolicyProperties properties) {
        this.engine = engine;
        this.cache = cache;
        this.properties = properties;
    }

    @Override
    public CallOutcome handle(ToolCall call, CallChain chain) {
        boolean onStalePolicy = cache.state() == PolicyStoreState.STALE;
        if (onStalePolicy) {
            requireStalePolicyAllowed(call);
        }
        PolicySnapshot policy = cache.current();
        PolicyDecision decision = engine.evaluate(policy, PolicyInput.from(call));
        CallTrace.recordPolicy(decision.ruleId() + (decision.allowed() || !policy.dryRun() ? "" : " (dry-run)"), decision.policyVersion());
        if (onStalePolicy && decision.allowed()) {
            CallTrace.recordFailSafe("stale-policy-allowed", "allowed by rule '" + decision.ruleId()
                    + "' of last known policy v" + decision.policyVersion() + " (" + cache.age().toSeconds() + " s old)");
        }

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

    /**
     * QA4 fail-safe. The policy store is unreachable, so the policy in memory may be out of date.
     * Only LOW-risk tools may use it, and only while it is younger than max-stale. Everything else fails closed.
     */
    private void requireStalePolicyAllowed(ToolCall call) {
        if (call.tool().riskLevel() != RiskLevel.LOW) {
            log.warn("FAIL-CLOSED agent={} tool={} risk={}: policy store unreachable",
                    call.context().agentId(), call.toolName(), call.tool().riskLevel());
            throw new PolicyStoreUnavailableException(call.toolName(), call.tool().riskLevel() + " risk tool");
        }
        if (cache.age().compareTo(properties.maxStale()) > 0) {
            log.warn("FAIL-CLOSED agent={} tool={}: last known policy is {} s old (max-stale {})",
                    call.context().agentId(), call.toolName(), cache.age().toSeconds(), properties.maxStale());
            throw new PolicyStoreUnavailableException(call.toolName(),
                    "last known policies are older than " + properties.maxStale());
        }
    }
}
