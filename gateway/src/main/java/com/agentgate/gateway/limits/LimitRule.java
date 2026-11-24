package com.agentgate.gateway.limits;

import com.agentgate.gateway.pipeline.ToolCall;
import com.agentgate.gateway.registry.AccessMode;
import java.time.Duration;
import java.util.List;

/**
 * One FR6 limit: "at most {max} calls per {window}, counted per agent or per tool".
 *
 * The limit types differ only in data (who is counted, the window length, the maximum, WRITE-only or not),
 * so they are plain values. No Strategy class per limit type is needed; see docs/patterns.md.
 *
 * @param id         name shown to the agent and stored in alerts, e.g. "agent-rate-per-minute"
 * @param subject    what is counted: the agent or the tool
 * @param window     fixed window length; windows are aligned to UTC (a day starts at 00:00 UTC)
 * @param max        calls allowed per window
 * @param writesOnly true = only WRITE calls count (blast-radius cap)
 */
public record LimitRule(String id, Subject subject, Duration window, int max, boolean writesOnly) {

    public enum Subject { AGENT, TOOL }

    public boolean appliesTo(ToolCall call) {
        return !writesOnly || call.tool().accessMode() == AccessMode.WRITE;
    }

    public String subjectOf(ToolCall call) {
        return subject == Subject.AGENT ? call.context().agentId() : call.toolName();
    }

    /** The standard FR6 limits, in the order they are checked. The first breach stops the call. */
    public static List<LimitRule> standard(LimitProperties p) {
        return List.of(
                new LimitRule("agent-rate-per-minute", Subject.AGENT, Duration.ofMinutes(1), p.agentPerMinute(), false),
                new LimitRule("tool-rate-per-minute", Subject.TOOL, Duration.ofMinutes(1), p.toolPerMinute(), false),
                new LimitRule("agent-daily-quota", Subject.AGENT, Duration.ofDays(1), p.agentDailyQuota(), false),
                new LimitRule("tool-daily-quota", Subject.TOOL, Duration.ofDays(1), p.toolDailyQuota(), false),
                new LimitRule("write-blast-radius-per-hour", Subject.AGENT, Duration.ofHours(1),
                        p.writesPerHourPerAgent(), true));
    }
}
