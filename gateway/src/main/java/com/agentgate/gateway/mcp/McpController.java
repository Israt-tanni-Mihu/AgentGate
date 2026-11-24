package com.agentgate.gateway.mcp;

import com.agentgate.gateway.identity.CallContext;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * The ONE MCP endpoint (Streamable HTTP, plain JSON answers). Agents send JSON-RPC messages with POST.
 * GET and DELETE are not mapped, so Spring answers 405, which the MCP spec allows for servers without
 * server-initiated streams. Security: same agent JWT as /api/agent/** (see SecurityConfig).
 */
@RestController
@RequestMapping("/mcp")
@Hidden   // JSON-RPC, not a REST API: it is described by the MCP spec, not by OpenAPI
public class McpController {

    private final McpMessageHandler handler;

    public McpController(McpMessageHandler handler) {
        this.handler = handler;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> receive(@RequestBody JsonNode message, CallContext callContext) {
        JsonNode answer = handler.handle(message, callContext);
        // A notification has no id and gets no answer: 202 Accepted with an empty body (MCP spec).
        return answer == null ? ResponseEntity.accepted().build() : ResponseEntity.ok(answer);
    }
}
