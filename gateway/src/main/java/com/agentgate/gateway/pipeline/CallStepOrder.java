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

    public static final int SCHEMA = 200;
    public static final int POLICY = 300;
    public static final int TRUNCATION = 400;
    public static final int REDACTION = 500;
    public static final int OWNERSHIP = 600;

    /** Always last: actually calls the downstream tool. */
    public static final int FORWARD = 10_000;

    private CallStepOrder() {
    }
}
