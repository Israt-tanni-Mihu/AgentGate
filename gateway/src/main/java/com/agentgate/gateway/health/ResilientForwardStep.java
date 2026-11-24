package com.agentgate.gateway.health;

import com.agentgate.gateway.pipeline.CallChain;
import com.agentgate.gateway.pipeline.CallOutcome;
import com.agentgate.gateway.pipeline.CallStep;
import com.agentgate.gateway.pipeline.ToolCall;
import com.agentgate.gateway.registry.AccessMode;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

/**
 * FR10 Decorator around the real forward step: same {@link CallStep} interface, but the call runs
 * (1) under the tool's circuit breaker and (2) with the tool's time limit.
 *
 * There is NO retry here, on purpose: a retried refund or SMS could happen twice. The agent gets the error
 * (503, 504 or 502) and decides.
 */
public class ResilientForwardStep implements CallStep {

    private final CallStep delegate;
    private final ToolBreakers breakers;
    /** Each call runs on its own virtual thread, so the time limiter can stop waiting and interrupt it. */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ResilientForwardStep(CallStep delegate, ToolBreakers breakers) {
        this.delegate = delegate;
        this.breakers = breakers;
    }

    @Override
    public CallOutcome handle(ToolCall call, CallChain chain) {
        String toolName = call.toolName();
        CircuitBreaker breaker = breakers.breakerFor(toolName);
        var limiter = breakers.limiterFor(call.tool());
        try {
            return breaker.executeCheckedSupplier(() ->
                    limiter.executeFutureSupplier(() -> executor.submit(() -> delegate.handle(call, chain))));
        } catch (CallNotPermittedException e) {
            throw new ToolUnavailableException(toolName, breakers.retryAfterSeconds());
        } catch (TimeoutException e) {
            throw new ToolTimeoutException(toolName, breakers.timeoutOf(call.tool()),
                    call.tool().accessMode() == AccessMode.WRITE);
        } catch (RuntimeException e) {
            throw e;   // ToolCallFailedException (502), InvalidArgumentsException (400) ... are already clear answers
        } catch (ExecutionException e) {
            throw asRuntime(e.getCause());
        } catch (Throwable e) {
            throw asRuntime(e);
        }
    }

    private static RuntimeException asRuntime(Throwable e) {
        if (e instanceof RuntimeException runtime) {
            return runtime;
        }
        if (e instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("Unexpected failure while calling the tool", e);
    }
}
