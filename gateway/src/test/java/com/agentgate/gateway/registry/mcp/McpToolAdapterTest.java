package com.agentgate.gateway.registry.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentgate.gateway.registry.AccessMode;
import com.agentgate.gateway.registry.RiskLevel;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Adapter: what an MCP server lists (tools/list) becomes our neutral ToolDefinition. No Spring, no database. */
class McpToolAdapterTest {

    private final McpToolAdapter adapter = new McpToolAdapter(new ObjectMapper());

    private static McpSchema.Tool tool(String name, Boolean readOnlyHint) {
        return new McpSchema.Tool(name, null, "desc of " + name, Map.of("type", "object"), null,
                readOnlyHint == null ? null : new McpSchema.ToolAnnotations(null, readOnlyHint, null, null, null, null),
                null);
    }

    @Test
    void aReadOnlyHintGivesReadAndNoHintGivesWriteBecauseWriteIsTheSafeGuess() {
        var result = adapter.adapt(List.of(tool("look", true), tool("change", null), tool("change2", false)),
                RiskLevel.MEDIUM);

        assertThat(result.tools()).extracting(t -> t.definition().name(), McpToolAdapter.Adapted::accessMode)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("look", AccessMode.READ),
                        org.assertj.core.groups.Tuple.tuple("change", AccessMode.WRITE),
                        org.assertj.core.groups.Tuple.tuple("change2", AccessMode.WRITE));
    }

    @Test
    void everyToolGetsTheServersDefaultRiskLevelAndNoHttpTarget() {
        var definition = adapter.adapt(List.of(tool("look", true)), RiskLevel.HIGH).tools().getFirst().definition();

        assertThat(definition.suggestedRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(definition.httpMethod()).isNull();
        assertThat(definition.path()).isNull();
        assertThat(definition.inputSchema().get("type").asString()).isEqualTo("object");
    }

    @Test
    void unusableNamesAreSkippedWithAReasonInsteadOfFailingTheImport() {
        var result = adapter.adapt(List.of(tool("ok_tool", true), tool("bad name!", true), tool("1st", true)),
                RiskLevel.LOW);

        assertThat(result.tools()).hasSize(1);
        assertThat(result.skipped()).hasSize(2);
        assertThat(result.skipped().getFirst()).contains("bad name!");
    }
}
