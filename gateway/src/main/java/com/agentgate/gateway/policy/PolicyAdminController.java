package com.agentgate.gateway.policy;

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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin API for policy versions (FR3). Rules of a version never change; to change rules, create a new draft. */
@RestController
@RequestMapping("/api/admin/policies")
@Tag(name = "Policies (admin)")
@SecurityRequirement(name = "adminBasic")
public class PolicyAdminController {

    private final PolicyAdminService service;

    public PolicyAdminController(PolicyAdminService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Create a new DRAFT version with a complete rule set")
    public ResponseEntity<PolicyVersionResponse> createDraft(@Valid @RequestBody CreatePolicyVersionRequest request) {
        PolicyVersionResponse created = service.createDraft(request);
        return ResponseEntity.created(URI.create("/api/admin/policies/" + created.version())).body(created);
    }

    @GetMapping
    @Operation(summary = "List versions (paged, newest first)")
    public PagedModel<PolicyVersionResponse> list(
            @ParameterObject @PageableDefault(size = 20, sort = "version", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return new PagedModel<>(service.list(pageable));
    }

    @GetMapping("/{version}")
    @Operation(summary = "Get one version with its rules")
    public PolicyVersionResponse get(@PathVariable int version) {
        return service.get(version);
    }

    @PostMapping("/{version}/activate")
    @Operation(summary = "Make this version the active one",
            description = "Takes effect for the next call. The previously active version becomes RETIRED. "
                    + "Activating a RETIRED version is a rollback.")
    public PolicyVersionResponse activate(@PathVariable int version) {
        return service.activate(version);
    }

    @PutMapping("/{version}/dry-run")
    @Operation(summary = "Turn dry-run on or off for a version",
            description = "In dry-run, calls this version would deny are only logged and let through.")
    public PolicyVersionResponse setDryRun(@PathVariable int version, @Valid @RequestBody DryRunRequest request) {
        return service.setDryRun(version, request.enabled());
    }
}
