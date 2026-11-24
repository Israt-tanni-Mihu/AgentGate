package com.agentgate.gateway.limits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentgate.gateway.identity.CallContext;
import com.agentgate.gateway.identity.Clearance;
import com.agentgate.gateway.pipeline.CallChain;
import com.agentgate.gateway.pipeline.CallOutcome;
import com.agentgate.gateway.pipeline.ToolCall;
import com.agentgate.gateway.registry.AccessMode;
import com.agentgate.gateway.registry.RiskLevel;
import com.agentgate.gateway.registry.ToolKind;
import com.agentgate.gateway.registry.ToolResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.ObjectMapper;

/** FR6 rules, tested without Spring or a database: a fake clock lets us jump over minutes and days. */
class LimitStepTest {

    private static final Instant START = Instant.parse("2026-11-24T10:00:00Z");
    private static final int BIG = 1_000;
    private static final CallChain ALLOW = call -> new CallOutcome.Completed(200, null);

    private ManualClock clock;
    private List<LimitBreachedEvent> events;

    @BeforeEach
    void setUp() {
        clock = new ManualClock(START);
        events = new ArrayList<>();
    }

    @Test
    void perMinuteRateLimitBlocksTheFourthCallAndAllowsAgainInTheNextMinute() {
        LimitStep step = step(new LimitProperties(3, BIG, BIG, BIG, BIG));
        ToolCall getOrder = call("support-bot", "getOrder", "GET");

        for (int i = 0; i < 3; i++) {
            assertThat(step.handle(getOrder, ALLOW)).isInstanceOf(CallOutcome.Completed.class);
        }
        LimitExceededException blocked = catchBlocked(step, getOrder);
        assertThat(blocked.getBody().getProperties()).containsEntry("limit", "agent-rate-per-minute");
        assertThat(blocked.getBody().getProperties()).containsEntry("resetsAt", START.plusSeconds(60));

        clock.advance(Duration.ofSeconds(60));
        assertThat(step.handle(getOrder, ALLOW)).isInstanceOf(CallOutcome.Completed.class);
    }

    @Test
    void dailyQuotaAllowsTheConfiguredNumberPerUtcDayAndResetsAtMidnight() {
        LimitStep step = step(new LimitProperties(BIG, BIG, 2, BIG, BIG));
        ToolCall getOrder = call("support-bot", "getOrder", "GET");

        step.handle(getOrder, ALLOW);
        step.handle(getOrder, ALLOW);
        LimitExceededException blocked = catchBlocked(step, getOrder);
        assertThat(blocked.getBody().getProperties()).containsEntry("limit", "agent-daily-quota")
                .containsEntry("resetsAt", Instant.parse("2026-11-25T00:00:00Z"));

        clock.advance(Duration.ofDays(1));
        assertThat(step.handle(getOrder, ALLOW)).isInstanceOf(CallOutcome.Completed.class);
    }

    @Test
    void toolRateLimitIsSharedByAllAgentsButAgentLimitsArePerAgent() {
        LimitStep step = step(new LimitProperties(BIG, 2, BIG, BIG, BIG));

        step.handle(call("support-bot", "getOrder", "GET"), ALLOW);
        step.handle(call("analytics-bot", "getOrder", "GET"), ALLOW);
        // The third call to getOrder is blocked, even though it comes from a different agent.
        LimitExceededException blocked = catchBlocked(step, call("analytics-bot", "getOrder", "GET"));
        assertThat(blocked.getBody().getProperties()).containsEntry("limit", "tool-rate-per-minute");
    }

    @Test
    void blastRadiusCapCountsOnlyWriteCalls() {
        LimitStep step = step(new LimitProperties(BIG, BIG, BIG, BIG, 2));
        ToolCall read = call("support-bot", "getOrder", "GET");
        ToolCall write = call("support-bot", "issueRefund", "POST");

        for (int i = 0; i < 5; i++) {
            step.handle(read, ALLOW);   // reads do not use up the write cap
        }
        step.handle(write, ALLOW);
        step.handle(write, ALLOW);
        LimitExceededException blocked = catchBlocked(step, write);
        assertThat(blocked.getBody().getProperties()).containsEntry("limit", "write-blast-radius-per-hour")
                .containsEntry("max", 2);
        assertThat(step.handle(read, ALLOW)).isInstanceOf(CallOutcome.Completed.class);
    }

    @Test
    void oneAlertIsPublishedPerWindowNotPerBlockedCall() {
        LimitStep step = step(new LimitProperties(1, BIG, BIG, BIG, BIG));
        ToolCall getOrder = call("support-bot", "getOrder", "GET");

        step.handle(getOrder, ALLOW);
        for (int i = 0; i < 4; i++) {
            catchBlocked(step, getOrder);
        }
        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.limitId()).isEqualTo("agent-rate-per-minute");
            assertThat(e.agentId()).isEqualTo("support-bot");
            assertThat(e.toolName()).isEqualTo("getOrder");
        });
    }

    // ---------- helpers ----------

    private LimitStep step(LimitProperties properties) {
        ApplicationEventPublisher publisher = event -> events.add((LimitBreachedEvent) event);
        return new LimitStep(properties, new RateCounters(), clock, publisher);
    }

    private static LimitExceededException catchBlocked(LimitStep step, ToolCall call) {
        try {
            step.handle(call, ALLOW);
        } catch (LimitExceededException e) {
            return e;
        }
        throw new AssertionError("expected the call to be blocked by a limit");
    }

    private static ToolCall call(String agentId, String toolName, String httpMethod) {
        ToolResponse tool = new ToolResponse(1L, toolName, "test tool", null, RiskLevel.LOW, "shop-team",
                ToolKind.REST, httpMethod, "http://localhost:8081", "/api/test", true,
                AccessMode.defaultFor(ToolKind.REST, httpMethod), null, START, START);
        return new ToolCall(tool, new ObjectMapper().createObjectNode(),
                new CallContext(agentId, null, Clearance.LOW));
    }

    /** A clock the test moves by hand. */
    static final class ManualClock extends Clock {
        private Instant now;

        ManualClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
