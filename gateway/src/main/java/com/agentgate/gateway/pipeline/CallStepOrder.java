package com.agentgate.gateway.pipeline;

/**
 * Positions of the steps (lower runs first, and wraps the steps with a higher number).
 * Gaps leave room for later steps without renumbering.
 *
 * Before the call: schema validation, then policy (the policy only sees well-formed arguments).
 * After the call: a step's code after {@code chain.proceed} runs in REVERSE order, so the step with the
 * LOWEST number handles the result LAST. Data flows FORWARD → OWNERSHIP → REDACTION → TRUNCATION → agent.
 */
public final class CallStepOrder {

    /** FR8: the very first check. A disabled agent or tool stops here, before any counter or policy runs. */
    public static final int KILL_SWITCH = 100;
    /** FR10: a tool whose circuit breaker is OPEN fails fast, before it uses up limits or asks a human. */
    public static final int TOOL_AVAILABILITY = 120;
    /** FR6: rate limits and quotas, before the schema check, so floods are counted and stopped early. */
    public static final int LIMITS = 150;
    public static final int SCHEMA = 200;
    public static final int POLICY = 300;
    /** FR4: after the policy, before the forward. HIGH-risk calls wait here for a human. */
    public static final int APPROVAL = 350;
    public static final int TRUNCATION = 400;
    public static final int REDACTION = 500;
    public static final int OWNERSHIP = 600;

    /** Always last: actually calls the downstream tool. */
    public static final int FORWARD = 10_000;

    private CallStepOrder() {
    }
}
