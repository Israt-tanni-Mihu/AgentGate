package com.agentgate.gateway.pipeline;

/**
 * CHAIN OF RESPONSIBILITY (see docs/patterns.md): one handler in the call pipeline.
 *
 * Every Spring bean implementing this interface becomes a step; {@code @Order} (see {@link CallStepOrder})
 * decides its position. A step can:
 * <ul>
 *   <li>allow: {@code return chain.proceed(call);}</li>
 *   <li>deny:  {@code return new CallOutcome.Denied(...);} (the next steps never run)</li>
 *   <li>wrap:  call {@code chain.proceed(call)} and look at the outcome afterwards (e.g. audit, FR7)</li>
 * </ul>
 * Adding a guard later = adding a new bean. Existing steps do not change.
 */
public interface CallStep {

    CallOutcome handle(ToolCall call, CallChain chain);
}
