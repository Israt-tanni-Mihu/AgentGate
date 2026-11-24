package com.agentgate.gateway.pipeline;

import com.agentgate.gateway.registry.ToolKind;
import com.agentgate.gateway.registry.mcp.McpDownstreamClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * STRATEGY for MCP tools (see {@link ToolInvoker}): sends tools/call to the downstream MCP server.
 *
 * Result mapping: the tool's own error ({@code isError: true}) becomes status 422, like an HTTP 4xx answer
 * (the tool works, the call was wrong, so the circuit breaker does not count it). A server that cannot be reached
 * or answers with a protocol error becomes {@link ToolCallFailedException} (502), which the breaker counts.
 */
@Component
public class McpToolInvoker implements ToolInvoker {

    private final McpDownstreamClient client;
    private final ObjectMapper objectMapper;

    public McpToolInvoker(McpDownstreamClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolKind kind() {
        return ToolKind.MCP;
    }

    @Override
    @SuppressWarnings("unchecked")
    public CallOutcome invoke(ToolCall call) {
        Map<String, Object> arguments = objectMapper.convertValue(call.arguments(), Map.class);
        McpSchema.CallToolResult result;
        try {
            result = client.callTool(call.tool().baseUrl(), call.tool().name(), arguments);
        } catch (RuntimeException e) {
            // Includes our own time limit interrupting the wait (handled by ResilientForwardStep before this).
            throw new ToolCallFailedException(call.tool().name(), "the MCP server could not be reached or failed ("
                    + e.getClass().getSimpleName() + ")");
        }
        int status = Boolean.TRUE.equals(result.isError()) ? 422 : 200;
        return new CallOutcome.Completed(status, toBody(result));
    }

    /** structuredContent if the tool sent it; else its text (parsed as JSON when it is JSON); else the raw content list. */
    private JsonNode toBody(McpSchema.CallToolResult result) {
        if (result.structuredContent() != null) {
            return objectMapper.valueToTree(result.structuredContent());
        }
        List<McpSchema.Content> content = result.content() == null ? List.of() : result.content();
        if (!content.isEmpty() && content.stream().allMatch(McpSchema.TextContent.class::isInstance)) {
            String text = String.join("\n", content.stream().map(c -> ((McpSchema.TextContent) c).text()).toList());
            try {
                return objectMapper.readTree(text);
            } catch (JacksonException notJson) {
                return objectMapper.getNodeFactory().stringNode(text);
            }
        }
        return objectMapper.valueToTree(content);
    }
}
