package com.agentgate.gateway.pipeline;

/** "The rest of the pipeline after me." A step calls {@code proceed} to pass the call on. */
@FunctionalInterface
public interface CallChain {

    CallOutcome proceed(ToolCall call);
}
