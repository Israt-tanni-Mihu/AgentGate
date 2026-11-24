package com.agentgate.gateway.pipeline;

import com.agentgate.gateway.identity.CallContext;
import com.agentgate.gateway.registry.ToolResponse;
import com.agentgate.gateway.registry.ToolService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.node.ObjectNode;

/**
 * Runs a tool call through every {@link CallStep} bean, in @Order.
 * Spring injects the steps already sorted by their @Order value.
 */
@Service
public class CallPipeline {

    private static final Logger log = LoggerFactory.getLogger(CallPipeline.class);

    private final List<CallStep> steps;
    private final ToolService toolService;

    public CallPipeline(List<CallStep> steps, ToolService toolService) {
        this.steps = List.copyOf(steps);
        this.toolService = toolService;
        log.info("Call pipeline: {}", this.steps.stream().map(s -> s.getClass().getSimpleName()).toList());
    }

    public CallOutcome execute(String toolName, ObjectNode arguments, CallContext context) {
        ToolResponse tool = toolService.findByName(toolName)
                .orElseThrow(() -> new UnknownToolException(toolName));
        if (!tool.enabled()) {
            throw new ToolDisabledException(toolName);
        }
        return proceedFrom(0, new ToolCall(tool, arguments, context));
    }

    /** Runs step number {@code index}; that step gets a chain that continues at index + 1. */
    private CallOutcome proceedFrom(int index, ToolCall call) {
        if (index >= steps.size()) {
            throw new IllegalStateException("No step completed the call; is ForwardStep missing?");
        }
        return steps.get(index).handle(call, nextCall -> proceedFrom(index + 1, nextCall));
    }

    List<CallStep> steps() {
        return steps;
    }
}
