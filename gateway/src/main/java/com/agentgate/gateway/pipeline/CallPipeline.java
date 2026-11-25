package com.agentgate.gateway.pipeline;

import com.agentgate.gateway.identity.CallContext;
import com.agentgate.gateway.registry.ToolResponse;
import com.agentgate.gateway.registry.ToolDirectory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.web.ErrorResponseException;
import tools.jackson.databind.node.ObjectNode;

/**
 * Runs a tool call through every {@link CallStep} bean, in @Order.
 * Spring injects the steps already sorted by their @Order value.
 *
 * FR7: every call that reaches this class ends with exactly one {@link CallCompleted} event, in a
 * try/finally, so a call that is denied or crashes in ANY step is still audited.
 */
@Service
public class CallPipeline {

    private static final Logger log = LoggerFactory.getLogger(CallPipeline.class);
    private static final int MAX_REASON_LENGTH = 500;

    private final List<CallStep> steps;
    private final ToolDirectory tools;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final CallMetrics metrics;

    public CallPipeline(List<CallStep> steps, ToolDirectory tools, ApplicationEventPublisher events, Clock clock,
                        CallMetrics metrics) {
        this.steps = List.copyOf(steps);
        this.tools = tools;
        this.events = events;
        this.clock = clock;
        this.metrics = metrics;
        log.info("Call pipeline: {}", this.steps.stream().map(s -> s.getClass().getSimpleName()).toList());
    }

    /** For callers that do not need the id before the call ends. */
    public CallOutcome execute(String toolName, ObjectNode arguments, CallContext context) {
        return execute(UUID.randomUUID(), toolName, arguments, context);
    }

    /** The caller makes the callId, so it can return it to the agent (X-Call-Id) even when the call fails. */
    public CallOutcome execute(UUID callId, String toolName, ObjectNode arguments, CallContext context) {
        CallTrace trace = new CallTrace(callId);
        Instant startedAt = clock.instant();
        long startNanos = System.nanoTime();
        CallOutcome outcome = null;
        Throwable failure = null;
        // C4/QA3: every log line of this call (on this thread) carries callId and agentId. Cleared in finally.
        MDC.put("callId", callId.toString());
        MDC.put("agentId", context.agentId());
        try {
            outcome = ScopedValue.where(CallTrace.CURRENT, trace).call(() -> run(toolName, arguments, context, trace));
            return outcome;
        } catch (RuntimeException | Error e) {
            failure = e;
            throw e;
        } finally {
            long latencyNanos = System.nanoTime() - startNanos;
            long latencyMs = latencyNanos / 1_000_000;
            publish(trace, startedAt, latencyMs, latencyNanos, toolName, arguments, context, outcome, failure);
            MDC.remove("callId");
            MDC.remove("agentId");
        }
    }

    private CallOutcome run(String toolName, ObjectNode arguments, CallContext context, CallTrace trace) {
        try {
            ToolResponse tool = tools.findByName(toolName)
                    .orElseThrow(() -> new UnknownToolException(toolName));
            if (!tool.enabled()) {
                throw new ToolDisabledException(toolName);
            }
            trace.recordTool(tool.name(), tool.riskLevel().name());
            return proceedFrom(0, new ToolCall(tool, arguments, context), trace);
        } catch (UnknownToolException | ToolDisabledException e) {
            trace.markDecidedBy(CallPipeline.class.getSimpleName());
            throw e;
        }
    }

    /** Runs step number {@code index}; that step gets a chain that continues at index + 1. */
    private CallOutcome proceedFrom(int index, ToolCall call, CallTrace trace) {
        if (index >= steps.size()) {
            throw new IllegalStateException("No step completed the call; is ForwardStep missing?");
        }
        CallStep step = steps.get(index);
        try {
            CallOutcome outcome = step.handle(call, nextCall -> proceedFrom(index + 1, nextCall, trace));
            if (outcome instanceof CallOutcome.Denied) {
                trace.markDecidedBy(step.getClass().getSimpleName());
            }
            return outcome;
        } catch (RuntimeException e) {
            trace.markDecidedBy(step.getClass().getSimpleName());   // e.g. SchemaValidationStep, LimitStep
            throw e;
        }
    }

    /** Never lets an audit problem break the agent's call. */
    private void publish(CallTrace trace, Instant startedAt, long latencyMs, long latencyNanos, String toolName, ObjectNode arguments,
                         CallContext context, CallOutcome outcome, Throwable failure) {
        try {
            CallCompleted event = toEvent(trace, startedAt, latencyMs, toolName, arguments, context, outcome, failure);
            log.info("call finished tool={} decision={} step={} rule={} status={} latencyMs={}", toolName,
                    event.decision(), event.decidedByStep(), event.ruleId(), event.httpStatus(), latencyMs);
            try {
                metrics.record(trace.toolName(), trace.risk(), event.decision(), trace.approvalId() != null,
                        event.decidedByStep(), Duration.ofNanos(latencyNanos));
            } catch (RuntimeException e) {
                log.warn("Could not record metrics for call {}", trace.callId(), e);   // metrics never break a call or its audit
            }
            events.publishEvent(event);
        } catch (RuntimeException e) {
            log.error("Could not publish the audit event for call {}", trace.callId(), e);
        }
    }

    private CallCompleted toEvent(CallTrace trace, Instant startedAt, long latencyMs, String toolName,
                                  ObjectNode arguments, CallContext context, CallOutcome outcome, Throwable failure) {
        String decision;
        String step;
        String rule;
        int status;
        String reason;
        if (outcome instanceof CallOutcome.Completed completed) {
            decision = "ALLOWED";
            step = trace.policyRuleId() != null ? "PolicyStep" : "ForwardStep";
            rule = trace.policyRuleId();
            status = completed.status();
            reason = "completed";
            if (trace.failSafeRule() != null) {   // QA4: say that the call ran on last known policies
                rule = trace.failSafeRule();
                reason = "completed; " + trace.failSafeDetail();
            }
        } else if (outcome instanceof CallOutcome.Denied denied) {
            decision = "DENIED";
            step = trace.decidingStep();
            rule = denied.rule();
            status = 403;
            reason = denied.reason();
        } else if (failure instanceof ErrorResponseException problem) {
            status = problem.getStatusCode().value();
            decision = status >= 500 ? "ERROR" : "DENIED";
            step = trace.decidingStep();
            rule = ruleOf(problem);
            reason = problem.getBody().getDetail();
        } else {
            decision = "ERROR";
            step = trace.decidingStep();
            rule = "internal-error";
            status = 500;
            reason = "unexpected error: " + (failure == null ? "no outcome" : failure.getClass().getSimpleName());
        }
        return new CallCompleted(trace.callId(), startedAt, context.agentId(), context.endUserId(), toolName,
                arguments, decision, step, rule, trace.policyVersion(), trace.approvalId(), trace.approvalState(),
                trace.approver(), trace.editedArguments(), latencyMs, status, shorten(reason));
    }

    /** The exceptions already carry their rule as a property ("rule", "limit"); otherwise use the title as a slug. */
    private static String ruleOf(ErrorResponseException problem) {
        var properties = problem.getBody().getProperties();
        for (String key : List.of("rule", "limit")) {
            if (properties != null && properties.get(key) != null) {
                return properties.get(key).toString();
            }
        }
        String title = problem.getBody().getTitle();
        return title == null ? "http-" + problem.getStatusCode().value()
                : title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
    }

    private static String shorten(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= MAX_REASON_LENGTH ? text : text.substring(0, MAX_REASON_LENGTH);
    }

    List<CallStep> steps() {
        return steps;
    }
}
