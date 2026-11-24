package com.agentgate.gateway.mcp;

import com.agentgate.gateway.catalog.ToolCatalogService;
import com.agentgate.gateway.identity.CallContext;
import com.agentgate.gateway.pipeline.CallOutcome;
import com.agentgate.gateway.pipeline.CallPipeline;
import com.agentgate.gateway.registry.AccessMode;
import com.agentgate.gateway.registry.ToolResponse;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * ADAPTER between the MCP protocol (JSON-RPC) and our own services.
 * It translates; it decides nothing. tools/list asks {@link ToolCatalogService}, tools/call runs
 * {@link CallPipeline}: the same two objects the REST endpoints use, so MCP cannot bypass any guard.
 *
 * Why not the Spring AI MCP server starter: its server keeps ONE tool list registered at startup for all clients,
 * but FR1/FR3 need a list per agent (policy, kill switch, breaker) that changes at runtime. A custom handler is
 * about 150 lines. NOTE: we did not compile against the starter; this follows from how its tool registry is
 * designed. Revisit in an ADR if a classmate finds a per-request hook.
 */
@Component
public class McpMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(McpMessageHandler.class);
    static final String LATEST_PROTOCOL = "2025-06-18";
    private static final List<String> SUPPORTED_PROTOCOLS = List.of(LATEST_PROTOCOL, "2025-03-26", "2024-11-05");
    private static final int PAGE_SIZE = 50;   // C3: lists are paged; MCP does it with an opaque "cursor"

    private final ToolCatalogService catalog;
    private final CallPipeline pipeline;
    private final ObjectMapper objectMapper;

    public McpMessageHandler(ToolCatalogService catalog, CallPipeline pipeline, ObjectMapper objectMapper) {
        this.catalog = catalog;
        this.pipeline = pipeline;
        this.objectMapper = objectMapper;
    }

    /** Returns the JSON-RPC answer, or null when the message was a notification (no answer allowed). */
    public JsonNode handle(JsonNode message, CallContext agent) {
        if (!message.isObject() || !"2.0".equals(message.path("jsonrpc").asString(null))
                || !message.path("method").isString()) {
            return error(null, -32600, "Invalid request: send one JSON-RPC 2.0 object with a 'method'");
        }
        JsonNode id = message.get("id");
        if (id == null || id.isNull()) {
            return null;   // a notification, e.g. notifications/initialized: nothing to do, we keep no session
        }
        String method = message.get("method").asString();
        JsonNode params = message.path("params");
        try {
            return switch (method) {
                case "initialize" -> success(id, initialize(params));
                case "ping" -> success(id, objectMapper.createObjectNode());
                case "tools/list" -> success(id, listTools(params, agent));
                case "tools/call" -> success(id, callTool(params, agent));
                default -> error(id, -32601, "Method not found: " + method);
            };
        } catch (BadParams e) {
            return error(id, -32602, e.getMessage());
        }
    }

    private ObjectNode initialize(JsonNode params) {
        String wanted = params.path("protocolVersion").asString("");
        ObjectNode result = objectMapper.createObjectNode();
        result.put("protocolVersion", SUPPORTED_PROTOCOLS.contains(wanted) ? wanted : LATEST_PROTOCOL);
        result.putObject("capabilities").putObject("tools").put("listChanged", false);
        result.putObject("serverInfo").put("name", "agentgate").put("version", "0.1.0");
        result.put("instructions", "AgentGate: every tool call is checked by policy, guarded, audited and may wait "
                + "for human approval. Your tool list only contains tools you may use right now.");
        return result;
    }

    // ---------- tools/list: PER AGENT, straight from the catalog ----------

    private ObjectNode listTools(JsonNode params, CallContext agent) {
        List<ToolResponse> visible = catalog.visibleTo(agent);
        int from = readCursor(params.path("cursor"), visible.size());
        int to = Math.min(from + PAGE_SIZE, visible.size());
        ArrayNode tools = objectMapper.createArrayNode();
        for (ToolResponse tool : visible.subList(from, to)) {
            ObjectNode entry = tools.addObject();
            entry.put("name", tool.name());
            entry.put("description", tool.description());
            entry.set("inputSchema", tool.inputSchema());
            entry.putObject("annotations").put("readOnlyHint", tool.accessMode() == AccessMode.READ);
        }
        ObjectNode result = objectMapper.createObjectNode();
        result.set("tools", tools);
        if (to < visible.size()) {
            result.put("nextCursor", String.valueOf(to));
        }
        return result;
    }

    private int readCursor(JsonNode cursor, int total) {
        if (cursor.isMissingNode() || cursor.isNull()) {
            return 0;
        }
        try {
            int offset = Integer.parseInt(cursor.asString(""));
            if (offset >= 0 && offset <= total) {
                return offset;
            }
        } catch (NumberFormatException ignored) {
            // falls through to the error below
        }
        throw new BadParams("Invalid cursor");
    }

    // ---------- tools/call: the SAME pipeline as POST /api/agent/calls/{tool} ----------

    private ObjectNode callTool(JsonNode params, CallContext agent) {
        String toolName = params.path("name").asString(null);
        if (toolName == null || toolName.isBlank()) {
            throw new BadParams("tools/call needs params.name");
        }
        JsonNode arguments = params.path("arguments");
        if (!arguments.isMissingNode() && !arguments.isNull() && !arguments.isObject()) {
            throw new BadParams("params.arguments must be a JSON object");
        }
        ObjectNode argumentObject = arguments.isObject() ? (ObjectNode) arguments : objectMapper.createObjectNode();

        UUID callId = UUID.randomUUID();
        try {
            CallOutcome outcome = pipeline.execute(callId, toolName, argumentObject, agent);
            return switch (outcome) {
                case CallOutcome.Completed done -> done.status() >= 400
                        ? toolResult(callId, true, "Call " + callId + ": the tool answered HTTP " + done.status()
                                + ": " + text(done.body()))
                        : toolResult(callId, false, text(done.body()));
                case CallOutcome.Denied denied -> toolResult(callId, true,
                        "Call " + callId + " was blocked: " + denied.reason() + " (rule: " + denied.rule() + ")");
            };
        } catch (ErrorResponseException problem) {
            // Guards, limits, approval, breaker ... throw RFC 9457 problems; the agent gets the same facts as in REST.
            var body = problem.getBody();
            var properties = body.getProperties();
            Object rule = properties == null ? null : properties.get("rule");
            if (rule == null && properties != null) {
                rule = properties.get("limit");
            }
            return toolResult(callId, true, "Call " + callId + " failed (HTTP " + problem.getStatusCode().value()
                    + "): " + body.getDetail() + (rule == null ? "" : " (rule: " + rule + ")"));
        } catch (RuntimeException e) {
            log.error("MCP call {} crashed", callId, e);
            return toolResult(callId, true, "Call " + callId + " failed: internal gateway error");
        }
    }

    private ObjectNode toolResult(UUID callId, boolean isError, String text) {
        ObjectNode result = objectMapper.createObjectNode();
        result.putArray("content").addObject().put("type", "text").put("text", text);
        result.put("isError", isError);
        result.putObject("_meta").put("agentgate/callId", callId.toString());
        return result;
    }

    private String text(JsonNode body) {
        return body == null ? "" : body.toString();
    }

    // ---------- JSON-RPC envelopes ----------

    private ObjectNode success(JsonNode id, JsonNode result) {
        ObjectNode answer = objectMapper.createObjectNode().put("jsonrpc", "2.0");
        answer.set("id", id);
        answer.set("result", result);
        return answer;
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode answer = objectMapper.createObjectNode().put("jsonrpc", "2.0");
        answer.set("id", id == null ? objectMapper.nullNode() : id);
        answer.putObject("error").put("code", code).put("message", message);
        return answer;
    }

    private static class BadParams extends RuntimeException {
        BadParams(String message) {
            super(message);
        }
    }
}
