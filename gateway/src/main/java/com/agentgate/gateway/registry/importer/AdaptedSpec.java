package com.agentgate.gateway.registry.importer;

import java.util.List;

/**
 * Result of one adapter run.
 *
 * @param tools   operations that became tool definitions
 * @param skipped operations that could not be converted, e.g. "POST /x: no operationId"
 */
public record AdaptedSpec(List<ToolDefinition> tools, List<String> skipped) {
}
