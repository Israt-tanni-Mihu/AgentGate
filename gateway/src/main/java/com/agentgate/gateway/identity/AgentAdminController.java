package com.agentgate.gateway.identity;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin API for agents (FR2). Protected by HTTP Basic admin login (see SecurityConfig). */
@RestController
@RequestMapping("/api/admin/agents")
@Tag(name = "Agents (admin)")
@SecurityRequirement(name = "adminBasic")
public class AgentAdminController {

    private final AgentService agentService;

    public AgentAdminController(AgentService agentService) {
        this.agentService = agentService;
    }

    @PostMapping
    @Operation(summary = "Register an agent", description = "The response contains the client secret. It is shown only this once.")
    public ResponseEntity<RegisteredAgentResponse> register(@Valid @RequestBody RegisterAgentRequest request) {
        RegisteredAgentResponse created = agentService.register(request);
        return ResponseEntity.created(URI.create("/api/admin/agents/" + created.clientId())).body(created);
    }

    @GetMapping("/{clientId}")
    @Operation(summary = "Get an agent by client id")
    public AgentResponse get(@PathVariable String clientId) {
        return agentService.get(clientId);
    }

    @GetMapping
    @Operation(summary = "List agents (paged)", description = "Use ?page=0&size=20&sort=clientId,asc. Max size is 100.")
    public PagedModel<AgentResponse> list(
            @ParameterObject @PageableDefault(size = 20, sort = "clientId", direction = Sort.Direction.ASC)
            Pageable pageable) {
        return new PagedModel<>(agentService.list(pageable));
    }

    @PostMapping("/{clientId}/disable")
    @Operation(summary = "Disable an agent", description = "It can no longer get tokens; tokens it already holds are rejected.")
    public AgentResponse disable(@PathVariable String clientId) {
        return agentService.disable(clientId);
    }
}
