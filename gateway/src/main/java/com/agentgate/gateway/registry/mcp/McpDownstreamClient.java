package com.agentgate.gateway.registry.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The gateway as an MCP CLIENT (Spring AI's MCP client library). One connected client per downstream server URL,
 * created on first use and reused (the MCP handshake is done once, not on every call).
 *
 * When a call fails, the client is thrown away; the next call connects again (a restarted server has a new session).
 * There is no retry here: a retried write could happen twice (same reason as in ResilientForwardStep).
 */
@Component
public class McpDownstreamClient {

    private static final Logger log = LoggerFactory.getLogger(McpDownstreamClient.class);
    /** Safety net only. The real per-tool time limit (FR10) is enforced around the call by ResilientForwardStep. */
    private static final Duration SAFETY_TIMEOUT = Duration.ofSeconds(10);

    private final ConcurrentMap<String, McpSyncClient> clients = new ConcurrentHashMap<>();

    /** All tools of the server (follows the paging cursor). Used by the import. */
    public List<McpSchema.Tool> listTools(String serverUrl) {
        return withClient(serverUrl, client -> {
            List<McpSchema.Tool> tools = new ArrayList<>();
            String cursor = null;
            do {
                McpSchema.ListToolsResult page = cursor == null ? client.listTools() : client.listTools(cursor);
                tools.addAll(page.tools());
                cursor = page.nextCursor();
            } while (cursor != null);
            return tools;
        });
    }

    /** MCP tools/call. {@code isError=true} in the result is the tool's own answer, not an exception. */
    public McpSchema.CallToolResult callTool(String serverUrl, String toolName, Map<String, Object> arguments) {
        return withClient(serverUrl, client -> client.callTool(new McpSchema.CallToolRequest(toolName, arguments)));
    }

    private <T> T withClient(String serverUrl, Function<McpSyncClient, T> action) {
        McpSyncClient client = clients.computeIfAbsent(serverUrl, this::connect);
        try {
            return action.apply(client);
        } catch (RuntimeException e) {
            if (clients.remove(serverUrl, client)) {
                closeQuietly(client);
            }
            throw e;
        }
    }

    private McpSyncClient connect(String serverUrl) {
        URI uri = URI.create(serverUrl);
        String base = uri.getScheme() + "://" + uri.getAuthority();
        String endpoint = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/mcp" : uri.getRawPath();
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(base)
                .endpoint(endpoint)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(SAFETY_TIMEOUT)
                .initializationTimeout(SAFETY_TIMEOUT)
                .clientInfo(new McpSchema.Implementation("agentgate-gateway", "0.1.0"))
                .build();
        client.initialize();
        log.info("Connected to downstream MCP server {}", serverUrl);
        return client;
    }

    private static void closeQuietly(McpSyncClient client) {
        try {
            client.close();
        } catch (RuntimeException ignored) {
            // the connection is already broken; nothing more to clean up
        }
    }
}
