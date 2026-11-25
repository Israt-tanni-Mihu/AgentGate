package com.agentgate.gateway.pipeline;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * C4: the call metrics, recorded once per call from {@link CallPipeline} (same place the audit event is made, so
 * the metrics and the audit log always describe the same calls). Meter names are listed in docs/metrics.md.
 *
 * Tag values are kept to a small fixed set: a tool name the agent made up (unknown tool) is NOT used as a tag,
 * otherwise an agent could create unlimited meters (memory leak / cardinality attack).
 */
@Component
class CallMetrics {

    static final String UNKNOWN = "unknown";

    private final MeterRegistry meters;

    CallMetrics(MeterRegistry meters) {
        this.meters = meters;
    }

    /**
     * @param tool     the registered tool name, or null if the name is not in the registry
     * @param risk     LOW / MEDIUM / HIGH, or null if the tool is unknown
     * @param decision ALLOWED / DENIED / ERROR (as audited)
     * @param held     the call went to a human for approval (whatever the human then decided)
     * @param step     the step that stopped the call (only used for DENIED)
     */
    void record(String tool, String risk, String decision, boolean held, String step, Duration latency) {
        String decisionTag = held ? "held" : decision.toLowerCase(Locale.ROOT);
        Counter.builder("agentgate.calls")
                .description("Tool calls that reached the pipeline")
                .tag("tool", tool == null ? UNKNOWN : tool)
                .tag("decision", decisionTag)
                .tag("risk", risk == null ? UNKNOWN : risk.toLowerCase(Locale.ROOT))
                .register(meters).increment();

        if ("DENIED".equals(decision)) {
            Counter.builder("agentgate.calls.blocked")
                    .description("Calls refused, by the step that refused them")
                    .tag("step", step == null ? UNKNOWN : step)
                    .register(meters).increment();
        }

        Timer.builder("agentgate.pipeline.latency")
                .description("Time inside the gateway pipeline per call (includes the tool call and, for held calls, the approval wait)")
                .tag("decision", decisionTag)
                .publishPercentileHistogram()
                .register(meters).record(latency);
    }
}
