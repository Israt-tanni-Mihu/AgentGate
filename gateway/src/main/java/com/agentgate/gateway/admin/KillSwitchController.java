package com.agentgate.gateway.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin API for the kill switch (FR8). Only HTTP concerns here; the rules live in KillSwitchService. */
@RestController
@RequestMapping("/api/admin/kill-switch")
@Tag(name = "Kill switch (admin)")
@SecurityRequirement(name = "adminBasic")
public class KillSwitchController {

    private final KillSwitchService service;

    public KillSwitchController(KillSwitchService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List kill switches (paged), engaged and released")
    public PagedModel<KillSwitchResponse> list(
            @ParameterObject @PageableDefault(size = 20, sort = "updatedAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return new PagedModel<>(service.list(pageable));
    }

    @PutMapping("/agents/{clientId}")
    @Operation(summary = "Disable or enable one agent")
    public KillSwitchResponse agent(@PathVariable String clientId, @Valid @RequestBody KillSwitchRequest request) {
        return service.set(KillSwitchScope.AGENT, clientId, request.engaged());
    }

    @PutMapping("/tools/{name}")
    @Operation(summary = "Disable or enable one tool")
    public KillSwitchResponse tool(@PathVariable String name, @Valid @RequestBody KillSwitchRequest request) {
        return service.set(KillSwitchScope.TOOL, name, request.engaged());
    }

    @PutMapping("/all-writes")
    @Operation(summary = "Disable or enable ALL WRITE tools (READ tools keep working)")
    public KillSwitchResponse allWrites(@Valid @RequestBody KillSwitchRequest request) {
        return service.set(KillSwitchScope.ALL_WRITES, null, request.engaged());
    }
}
