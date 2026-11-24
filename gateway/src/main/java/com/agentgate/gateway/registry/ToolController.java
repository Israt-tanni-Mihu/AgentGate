package com.agentgate.gateway.registry;

import com.agentgate.gateway.registry.importer.ImportRequest;
import com.agentgate.gateway.registry.importer.ImportResult;
import com.agentgate.gateway.registry.importer.ToolImportService;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin REST API of the tool registry (FR1). Only HTTP concerns here; rules live in the services. */
@RestController
@RequestMapping("/api/admin/tools")
@Tag(name = "Tool registry (admin)")
public class ToolController {

    private final ToolService toolService;
    private final ToolImportService importService;

    public ToolController(ToolService toolService, ToolImportService importService) {
        this.toolService = toolService;
        this.importService = importService;
    }

    @PostMapping
    @Operation(summary = "Register a tool")
    public ResponseEntity<ToolResponse> create(@Valid @RequestBody ToolRequest request) {
        ToolResponse created = toolService.create(request);
        return ResponseEntity.created(URI.create("/api/admin/tools/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a tool by id")
    public ToolResponse get(@PathVariable Long id) {
        return toolService.get(id);
    }

    @GetMapping
    @Operation(summary = "List tools (paged)", description = "Use ?page=0&size=20&sort=name,asc. Max size is 100.")
    public PagedModel<ToolResponse> list(
            @ParameterObject @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC)
            Pageable pageable) {
        return new PagedModel<>(toolService.list(pageable));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Replace a tool")
    public ToolResponse update(@PathVariable Long id, @Valid @RequestBody ToolRequest request) {
        return toolService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a tool")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        toolService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/import")
    @Operation(summary = "Import REST tools from an OpenAPI 3 spec",
            description = "Idempotent: tools are matched by name, so importing twice updates instead of duplicating.")
    public ImportResult importTools(@Valid @RequestBody ImportRequest request) {
        return importService.importFromUrl(request);
    }
}
