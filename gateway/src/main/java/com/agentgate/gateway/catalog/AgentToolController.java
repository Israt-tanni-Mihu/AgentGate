package com.agentgate.gateway.catalog;

import com.agentgate.gateway.identity.CallContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The tool list of the calling agent (plain REST; the MCP endpoint will offer the same list later). */
@RestController
@RequestMapping("/api/agent/tools")
@Tag(name = "Tool list (agent)")
@SecurityRequirement(name = "agentToken")
public class AgentToolController {

    private final ToolCatalogService catalog;

    public AgentToolController(ToolCatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    @Operation(summary = "Tools this agent may use right now (paged)",
            description = "Hidden: tools the policy never allows for this agent, tools stopped by a kill switch, "
                    + "and tools whose circuit breaker is OPEN (they return when the tool recovers).")
    public PagedModel<AgentToolResponse> list(
            @Parameter(hidden = true) CallContext callContext,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        List<AgentToolResponse> all = catalog.visibleTo(callContext).stream().map(AgentToolResponse::of).toList();
        int from = (int) Math.min(pageable.getOffset(), all.size());
        int to = Math.min(from + pageable.getPageSize(), all.size());
        return new PagedModel<>(new PageImpl<>(all.subList(from, to), pageable, all.size()));
    }
}
