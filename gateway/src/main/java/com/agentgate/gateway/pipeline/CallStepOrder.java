package com.agentgate.gateway.pipeline;

/**
 * Positions of the steps (lower runs first). Gaps leave room for later steps without renumbering,
 * e.g. kill switch and limits before POLICY, schema guard and approval between POLICY and FORWARD.
 */
public final class CallStepOrder {

    public static final int POLICY = 300;

    /** Always last: actually calls the downstream tool. */
    public static final int FORWARD = 10_000;

    private CallStepOrder() {
    }
}
