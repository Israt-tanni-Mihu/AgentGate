package com.agentgate.gateway.registry.mcp;

import com.agentgate.gateway.registry.AccessMode;
import com.agentgate.gateway.registry.RiskLevel;
import com.agentgate.gateway.registry.importer.ToolDefinition;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * ADAPTER (see docs/patterns.md): converts what an MCP server says (tools/list: {@code McpSchema.Tool}) into
 * our own {@link ToolDefinition}, the same neutral shape the OpenAPI adapter produces.
 * The rest of the registry never sees MCP library types.
 */
@Component
public class McpToolAdapter {

    private static final Pattern VALID_NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]{0,63}$");

    /** A converted tool plus what MCP says about reading vs writing. */
    public record Adapted(ToolDefinition definition, AccessMode accessMode) {
    }

    public record Result(List<Adapted> tools, List<String> skipped) {
    }

    private final ObjectMapper objectMapper;

    public McpToolAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Result adapt(List<McpSchema.Tool> mcpTools, RiskLevel defaultRisk) {
        List<Adapted> tools = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (McpSchema.Tool tool : mcpTools) {
            if (tool.name() == null || !VALID_NAME.matcher(tool.name()).matches()) {
                skipped.add(tool.name() + ": name is not usable (letters, digits, '_' '-', max 64, must start with a letter)");
                continue;
            }
            JsonNode schema = tool.inputSchema() == null
                    ? objectMapper.createObjectNode().put("type", "object")
                    : objectMapper.valueToTree(tool.inputSchema());
            String description = tool.description() != null ? tool.description()
                    : tool.title() != null ? tool.title() : tool.name();
            // Only a server that says "readOnlyHint: true" gets READ. No hint = WRITE (fail safe, FR6).
            boolean readOnly = tool.annotations() != null && Boolean.TRUE.equals(tool.annotations().readOnlyHint());
            ToolDefinition definition = new ToolDefinition(tool.name(), cut(description), schema, defaultRisk, null, null);
            tools.add(new Adapted(definition, readOnly ? AccessMode.READ : AccessMode.WRITE));
        }
        return new Result(tools, skipped);
    }

    private static String cut(String text) {
        return text.length() <= 2000 ? text : text.substring(0, 2000);
    }
}
