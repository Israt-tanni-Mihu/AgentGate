package com.agentgate.mocktools.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** The mock MCP server really speaks MCP: a real MCP client lists and calls track_parcel over HTTP. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ParcelMcpToolsTest {

    @LocalServerPort
    int port;

    @Test
    void trackParcelIsListedAsReadOnlyAndAnswersWithTheParcelStatus() {
        var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port).endpoint("/mcp").build();
        try (McpSyncClient client = McpClient.sync(transport).build()) {
            client.initialize();

            McpSchema.Tool tool = client.listTools().tools().getFirst();
            assertThat(tool.name()).isEqualTo("track_parcel");
            assertThat(tool.annotations().readOnlyHint()).isTrue();

            McpSchema.CallToolResult result = client.callTool(
                    new McpSchema.CallToolRequest("track_parcel", Map.of("trackingNumber", "BD1001")));
            assertThat(result.isError()).isNotEqualTo(true);
            assertThat(result.content().toString()).contains("Delivered to Dhaka");
        }
    }
}
