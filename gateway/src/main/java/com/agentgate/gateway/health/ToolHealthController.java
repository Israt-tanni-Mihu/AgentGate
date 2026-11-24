package com.agentgate.gateway.health;

import com.agentgate.gateway.registry.ToolResponse;
import com.agentgate.gateway.registry.ToolService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** FR10: what the circuit breaker thinks of each tool. (A fixed path wins over ToolController's "/{id}".) */
@RestController
@RequestMapping("/api/admin/tools/health")
@Tag(name = "Downstream health (admin)")
@SecurityRequirement(name = "adminBasic")
public class ToolHealthController {

    private final ToolService toolService;
    private final ToolBreakers breakers;

    public ToolHealthController(ToolService toolService, ToolBreakers breakers) {
        this.toolService = toolService;
        this.breakers = breakers;
    }

    @GetMapping
    @Operation(summary = "Circuit breaker state of every tool (paged)",
            description = "A tool that was never called is CLOSED. OPEN tools are hidden from agents' tool lists.")
    public PagedModel<ToolHealthResponse> health(
            @ParameterObject @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC)
            Pageable pageable) {
        return new PagedModel<>(toolService.list(pageable).map(this::toHealth));
    }

    private ToolHealthResponse toHealth(ToolResponse tool) {
        long timeoutMs = breakers.timeoutOf(tool).toMillis();
        return breakers.find(tool.name())
                .map(breaker -> {
                    CircuitBreaker.Metrics metrics = breaker.getMetrics();
                    float rate = metrics.getFailureRate();   // -1 while there are too few calls
                    return new ToolHealthResponse(tool.name(), tool.accessMode(), breaker.getState().name(),
                            !breakers.isOpen(tool.name()), rate < 0 ? null : rate,
                            metrics.getNumberOfBufferedCalls(), metrics.getNumberOfFailedCalls(),
                            metrics.getNumberOfNotPermittedCalls(), timeoutMs);
                })
                .orElseGet(() -> new ToolHealthResponse(tool.name(), tool.accessMode(), "CLOSED", true,
                        null, 0, 0, 0, timeoutMs));
    }
}
