package com.agentgate.gateway.registry.importer;

import com.agentgate.gateway.registry.AccessMode;
import com.agentgate.gateway.registry.Tool;
import com.agentgate.gateway.registry.ToolKind;
import com.agentgate.gateway.registry.ToolRepository;
import com.agentgate.gateway.registry.importer.ImportResult.Item;
import com.agentgate.gateway.registry.importer.ImportResult.Outcome;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Imports tools from a spec URL (FR1). Steps: download → pick an adapter → adapt → upsert by tool name.
 *
 * Idempotent: the tool name is the key, so importing the same spec twice never creates duplicates.
 * On re-import only the technical fields (description, schema, method, path, baseUrl) are refreshed.
 * riskLevel and enabled are governance decisions of the admin, so a spec change can never lower them.
 */
@Service
public class ToolImportService {

    private final List<ToolSpecAdapter> adapters;
    private final SpecDownloader downloader;
    private final ToolRepository repository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public ToolImportService(List<ToolSpecAdapter> adapters, SpecDownloader downloader, ToolRepository repository,
                             ObjectMapper objectMapper, TransactionTemplate transactionTemplate) {
        this.adapters = adapters;
        this.downloader = downloader;
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    public ImportResult importFromUrl(ImportRequest request) {
        // Download outside the DB transaction: a slow network must not hold a DB connection.
        String specContent = downloader.download(request.specUrl());
        return importSpec(specContent, request.ownerTeam(), request.baseUrl());
    }

    public ImportResult importSpec(String specContent, String ownerTeam, String baseUrl) {
        ToolSpecAdapter adapter = adapters.stream()
                .filter(candidate -> candidate.supports(specContent))
                .findFirst()
                .orElseThrow(() -> new SpecImportException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "Unsupported spec format. Supported: "
                                + adapters.stream().map(ToolSpecAdapter::formatName).toList()));

        AdaptedSpec adapted = adapter.adapt(specContent);

        List<Item> items = new ArrayList<>();
        // All-or-nothing: if saving one tool fails, none of this import is kept.
        transactionTemplate.executeWithoutResult(status -> {
            for (ToolDefinition definition : adapted.tools()) {
                items.add(upsert(definition, ownerTeam, baseUrl));
            }
        });
        for (String reason : adapted.skipped()) {
            items.add(new Item(null, Outcome.SKIPPED, reason));
        }
        return ImportResult.of(adapter.formatName(), items);
    }

    private Item upsert(ToolDefinition definition, String ownerTeam, String baseUrl) {
        Optional<Tool> existing = repository.findByName(definition.name());

        if (existing.isEmpty()) {
            Tool tool = new Tool();
            tool.setName(definition.name());
            tool.setKind(ToolKind.REST);
            tool.setOwnerTeam(ownerTeam);
            tool.setRiskLevel(definition.suggestedRiskLevel());
            tool.setEnabled(true);
            copyTechnicalFields(definition, baseUrl, tool);
            repository.save(tool);
            return new Item(definition.name(), Outcome.CREATED, null);
        }

        Tool tool = existing.get();
        if (tool.getKind() != ToolKind.REST || !tool.getOwnerTeam().equals(ownerTeam)) {
            // Never take over another team's tool (or an MCP tool) just because the names match.
            return new Item(definition.name(), Outcome.SKIPPED,
                    "name already used by a " + tool.getKind() + " tool of team '" + tool.getOwnerTeam() + "'");
        }
        if (sameTechnicalFields(definition, baseUrl, tool)) {
            return new Item(definition.name(), Outcome.SKIPPED, "unchanged");
        }
        copyTechnicalFields(definition, baseUrl, tool);   // JPA saves the change at commit (dirty checking)
        return new Item(definition.name(), Outcome.UPDATED, null);
    }

    private void copyTechnicalFields(ToolDefinition definition, String baseUrl, Tool tool) {
        // FR6: recompute READ/WRITE only when the method changes, so an admin's override survives other re-imports.
        if (!Objects.equals(tool.getHttpMethod(), definition.httpMethod())) {
            tool.setAccessMode(AccessMode.defaultFor(ToolKind.REST, definition.httpMethod()));
        }
        tool.setDescription(definition.description());
        tool.setInputSchema(objectMapper.writeValueAsString(definition.inputSchema()));
        tool.setHttpMethod(definition.httpMethod());
        tool.setPath(definition.path());
        tool.setBaseUrl(baseUrl);
    }

    private boolean sameTechnicalFields(ToolDefinition definition, String baseUrl, Tool tool) {
        // Compare schemas as JSON trees: PostgreSQL jsonb may reorder keys, so string comparison is not reliable.
        JsonNode storedSchema = objectMapper.readTree(tool.getInputSchema());
        return Objects.equals(tool.getDescription(), definition.description())
                && Objects.equals(storedSchema, definition.inputSchema())
                && Objects.equals(tool.getHttpMethod(), definition.httpMethod())
                && Objects.equals(tool.getPath(), definition.path())
                && Objects.equals(tool.getBaseUrl(), baseUrl);
    }
}
