package com.agentgate.gateway.registry.mcp;

import com.agentgate.gateway.registry.importer.ImportResult;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin API for downstream MCP servers (FR1). Secured by the /api/admin/** chain (HTTP Basic). */
@RestController
@RequestMapping("/api/admin/mcp-servers")
@Tag(name = "MCP servers (admin)")
@SecurityRequirement(name = "adminBasic")
public class McpServerController {

    private final McpServerService service;

    public McpServerController(McpServerService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Register a downstream MCP server")
    public ResponseEntity<McpServerResponse> register(@Valid @RequestBody McpServerRequest request) {
        McpServerResponse created = service.register(request);
        return ResponseEntity.created(URI.create("/api/admin/mcp-servers/" + created.id())).body(created);
    }

    @GetMapping
    @Operation(summary = "List MCP servers (paged)")
    public PagedModel<McpServerResponse> list(
            @ParameterObject @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC)
            Pageable pageable) {
        return new PagedModel<>(service.list(pageable));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an MCP server")
    public McpServerResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Unregister an MCP server (its imported tools stay)")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/import")
    @Operation(summary = "Import the server's tools (MCP tools/list) into the tool registry",
            description = "Idempotent. New tools get the server's default risk level; change one with PUT /api/admin/tools/{id}.")
    public ImportResult importTools(@PathVariable Long id) {
        return service.importTools(id);
    }
}
