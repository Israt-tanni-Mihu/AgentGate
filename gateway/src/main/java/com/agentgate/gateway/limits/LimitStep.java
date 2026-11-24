package com.agentgate.gateway.limits;

import com.agentgate.gateway.limits.RateCounters.Attempt;
import com.agentgate.gateway.pipeline.CallChain;
import com.agentgate.gateway.pipeline.CallOutcome;
import com.agentgate.gateway.pipeline.CallStep;
import com.agentgate.gateway.pipeline.CallStepOrder;
import com.agentgate.gateway.pipeline.ToolCall;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * FR6 step: rate limits, daily quotas and the WRITE blast-radius cap. It runs after the kill switch and before
 * the schema check, so floods of bad calls are counted and stopped early.
 *
 * A breach throws {@link LimitExceededException} (429). The first breach in each window also publishes a
 * {@link LimitBreachedEvent}, so there is one alert per breach, not one per blocked call.
 */
@Component
@Order(CallStepOrder.LIMITS)
public class LimitStep implements CallStep {

    private static final Logger log = LoggerFactory.getLogger(LimitStep.class);

    private final List<LimitRule> rules;
    private final RateCounters counters;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public LimitStep(LimitProperties properties, RateCounters counters, Clock clock,
                     ApplicationEventPublisher events) {
        this.rules = LimitRule.standard(properties);
        this.counters = counters;
        this.clock = clock;
        this.events = events;
    }

    @Override
    public CallOutcome handle(ToolCall call, CallChain chain) {
        Instant now = clock.instant();
        for (LimitRule rule : rules) {
            if (!rule.appliesTo(call)) {
                continue;
            }
            Attempt attempt = counters.countAttempt(rule.id() + ":" + rule.subjectOf(call), now, rule.window());
            if (attempt.count() > rule.max()) {
                if (attempt.count() == rule.max() + 1) {   // first breach in this window only
                    events.publishEvent(new LimitBreachedEvent(rule.id(), call.context().agentId(),
                            call.toolName(), rule.max(), now));
                }
                log.warn("LIMIT agent={} tool={} limit={} max={} resetsAt={}",
                        call.context().agentId(), call.toolName(), rule.id(), rule.max(), attempt.resetsAt());
                throw new LimitExceededException(rule.id(), rule.max(), call.toolName(), attempt.resetsAt(), now);
            }
        }
        return chain.proceed(call);
    }
}
