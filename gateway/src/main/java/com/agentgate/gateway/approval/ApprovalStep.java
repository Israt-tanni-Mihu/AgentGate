package com.agentgate.gateway.approval;

import com.agentgate.gateway.guard.SchemaValidationStep;
import com.agentgate.gateway.pipeline.CallChain;
import com.agentgate.gateway.pipeline.CallOutcome;
import com.agentgate.gateway.pipeline.CallStep;
import com.agentgate.gateway.pipeline.CallStepOrder;
import com.agentgate.gateway.pipeline.ToolCall;
import com.agentgate.gateway.policy.PolicyStep;
import com.agentgate.gateway.registry.RiskLevel;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ObjectNode;

/**
 * FR4 step, after the policy and before the forward. A HIGH-risk call is saved as a PENDING request and the
 * agent's HTTP call WAITS here (a virtual thread, so waiting is cheap) until a human decides, the kill switch
 * cancels it, or the timeout expires it. The agent always gets a clear answer.
 *
 * Security rule: edited arguments are untrusted. They run through the schema check AND the policy again
 * before anything is forwarded, so an approver's edit can never get around the guards.
 */
@Component
@Order(CallStepOrder.APPROVAL)
public class ApprovalStep implements CallStep {

    private static final Logger log = LoggerFactory.getLogger(ApprovalStep.class);

    private final ApprovalService service;
    private final ApprovalWaiters waiters;
    private final ApprovalProperties properties;
    private final SchemaValidationStep schemaStep;
    private final PolicyStep policyStep;

    public ApprovalStep(ApprovalService service, ApprovalWaiters waiters, ApprovalProperties properties,
                        SchemaValidationStep schemaStep, PolicyStep policyStep) {
        this.service = service;
        this.waiters = waiters;
        this.properties = properties;
        this.schemaStep = schemaStep;
        this.policyStep = policyStep;
    }

    @Override
    public CallOutcome handle(ToolCall call, CallChain chain) {
        if (call.tool().riskLevel() != RiskLevel.HIGH) {
            return chain.proceed(call);
        }
        UUID id = UUID.randomUUID();
        CompletableFuture<ApprovalDecided> future = waiters.register(id);   // BEFORE saving: no decision can be missed
        try {
            service.open(id, call, properties.timeout());
            log.info("HOLD agent={} tool={} approvalId={}", call.context().agentId(), call.toolName(), id);
            ApprovalDecided decision = await(id, future);
            return continueAfter(decision, call, chain);
        } finally {
            waiters.remove(id);
        }
    }

    private ApprovalDecided await(UUID id, CompletableFuture<ApprovalDecided> future) {
        try {
            return future.get(properties.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            try {
                service.expire(id);
            } catch (IllegalApprovalTransitionException alreadyDecided) {
                // A human decided in the last moment. Their decision wins; it is already on its way.
            }
            return getAfterTimeout(future);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for approval " + id, e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Approval " + id + " failed", e.getCause());
        }
    }

    private ApprovalDecided getAfterTimeout(CompletableFuture<ApprovalDecided> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Approval was expired but no decision arrived", e);
        }
    }

    private CallOutcome continueAfter(ApprovalDecided decision, ToolCall call, CallChain chain) {
        return switch (decision.state()) {
            case APPROVED -> chain.proceed(call);
            case APPROVED_WITH_EDITS -> proceedWithEdits(decision, call, chain);
            case REJECTED -> notApproved("approval-rejected", "a human approver rejected the call", decision);
            case EXPIRED -> notApproved("approval-expired", "nobody approved the call in time", decision);
            case CANCELLED -> notApproved("approval-cancelled", "the call was cancelled by the kill switch", decision);
            case PENDING -> throw new IllegalStateException("A decision cannot be PENDING");
        };
    }

    /** The edited arguments pass the SAME schema check and policy as any call, then continue down the chain. */
    private CallOutcome proceedWithEdits(ApprovalDecided decision, ToolCall call, CallChain chain) {
        ToolCall edited = new ToolCall(call.tool(), (ObjectNode) decision.editedArguments(), call.context());
        CallOutcome outcome = schemaStep.handle(edited, afterSchema -> policyStep.handle(afterSchema, chain));
        if (outcome instanceof CallOutcome.Denied denied) {
            Map<String, Object> details = new LinkedHashMap<>(denied.details());
            details.putAll(resultFields(decision));
            return new CallOutcome.Denied(denied.rule(), "the approver's edited arguments were blocked: " + denied.reason(),
                    details);
        }
        return outcome;
    }

    private static CallOutcome notApproved(String rule, String reason, ApprovalDecided decision) {
        String detail = decision.reason() == null ? reason : reason + " (" + decision.reason() + ")";
        return new CallOutcome.Denied(rule, detail, resultFields(decision));
    }

    /** The fields every non-approved answer carries: approvalId, final state, reason. */
    private static Map<String, Object> resultFields(ApprovalDecided decision) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("approvalId", decision.approvalId());
        fields.put("state", decision.state());
        fields.put("reason", decision.reason());
        return fields;
    }
}
