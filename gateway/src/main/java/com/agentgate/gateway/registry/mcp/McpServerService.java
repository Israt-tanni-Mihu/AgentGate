package com.agentgate.gateway.registry.mcp;

import com.agentgate.gateway.registry.Tool;
import com.agentgate.gateway.registry.ToolKind;
import com.agentgate.gateway.registry.ToolRepository;
import com.agentgate.gateway.registry.importer.ImportResult;
import com.agentgate.gateway.registry.importer.ImportResult.Item;
import com.agentgate.gateway.registry.importer.ImportResult.Outcome;
import com.agentgate.gateway.registry.importer.SpecImportException;
import com.agentgate.gateway.registry.importer.ToolDefinition;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * FR1: register downstream MCP servers and import their tools into the tool registry.
 *
 * Same rules as the OpenAPI import: idempotent (tool name = key), and a re-import refreshes only technical fields.
 * riskLevel, enabled and an admin's accessMode choice are never changed by an import.
 */
@Service
public class McpServerService {

    private final McpServerRepository servers;
    private final ToolRepository tools;
    private final McpDownstreamClient client;
    private final McpToolAdapter adapter;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transaction;

    public McpServerService(McpServerRepository servers, ToolRepository tools, McpDownstreamClient client,
                            McpToolAdapter adapter, ObjectMapper objectMapper, TransactionTemplate transaction) {
        this.servers = servers;
        this.tools = tools;
        this.client = client;
        this.adapter = adapter;
        this.objectMapper = objectMapper;
        this.transaction = transaction;
    }

    public McpServerResponse register(McpServerRequest request) {
        if (servers.existsByName(request.name())) {
            throw new DuplicateMcpServerException(request.name());
        }
        McpServer server = new McpServer();
        server.setName(request.name());
        server.setUrl(request.url());
        server.setOwnerTeam(request.ownerTeam());
        server.setDefaultRiskLevel(request.defaultRiskLevel());
        return McpServerResponse.from(servers.save(server));
    }

    public McpServerResponse get(Long id) {
        return McpServerResponse.from(find(id));
    }

    public Page<McpServerResponse> list(Pageable pageable) {
        return servers.findAll(pageable).map(McpServerResponse::from);
    }

    public void delete(Long id) {
        servers.delete(find(id));   // its imported tools stay in the registry; the admin decides about them
    }

    /** tools/list on the server, then upsert by tool name. The network call happens outside the DB transaction. */
    public ImportResult importTools(Long id) {
        McpServer server = find(id);
        List<McpSchema.Tool> remoteTools;
        try {
            remoteTools = client.listTools(server.getUrl());
        } catch (RuntimeException e) {
            throw new SpecImportException(HttpStatus.BAD_GATEWAY,
                    "Could not list tools of MCP server '" + server.getName() + "': " + e.getMessage());
        }
        McpToolAdapter.Result adapted = adapter.adapt(remoteTools, server.getDefaultRiskLevel());

        List<Item> items = new ArrayList<>();
        transaction.executeWithoutResult(status -> {
            for (McpToolAdapter.Adapted tool : adapted.tools()) {
                items.add(upsert(server, tool));
            }
        });
        adapted.skipped().forEach(reason -> items.add(new Item(null, Outcome.SKIPPED, reason)));
        return ImportResult.of("mcp-tools-list", items);
    }

    private Item upsert(McpServer server, McpToolAdapter.Adapted adapted) {
        ToolDefinition definition = adapted.definition();
        Optional<Tool> existing = tools.findByName(definition.name());
        if (existing.isEmpty()) {
            Tool tool = new Tool();
            tool.setName(definition.name());
            tool.setKind(ToolKind.MCP);
            tool.setOwnerTeam(server.getOwnerTeam());
            tool.setRiskLevel(definition.suggestedRiskLevel());   // = the server's default risk level
            tool.setAccessMode(adapted.accessMode());
            tool.setEnabled(true);
            copyTechnicalFields(definition, server, tool);
            tools.save(tool);
            return new Item(definition.name(), Outcome.CREATED, null);
        }
        Tool tool = existing.get();
        if (tool.getKind() != ToolKind.MCP || !server.getUrl().equals(tool.getBaseUrl())) {
            // Never take over a REST tool or another server's tool just because the names match.
            return new Item(definition.name(), Outcome.SKIPPED,
                    "name already used by a " + tool.getKind() + " tool of team '" + tool.getOwnerTeam() + "'");
        }
        JsonNode stored = objectMapper.readTree(tool.getInputSchema());
        if (Objects.equals(tool.getDescription(), definition.description())
                && Objects.equals(stored, definition.inputSchema())) {
            return new Item(definition.name(), Outcome.SKIPPED, "unchanged");
        }
        copyTechnicalFields(definition, server, tool);   // saved at commit (dirty checking)
        return new Item(definition.name(), Outcome.UPDATED, null);
    }

    private void copyTechnicalFields(ToolDefinition definition, McpServer server, Tool tool) {
        tool.setDescription(definition.description());
        tool.setInputSchema(objectMapper.writeValueAsString(definition.inputSchema()));
        tool.setBaseUrl(server.getUrl());
    }

    private McpServer find(Long id) {
        return servers.findById(id).orElseThrow(() -> new McpServerNotFoundException(id));
    }
}
