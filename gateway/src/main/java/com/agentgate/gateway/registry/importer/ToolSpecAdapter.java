package com.agentgate.gateway.registry.importer;

/**
 * Adapter pattern — the "Target" interface.
 *
 * The importer only understands {@link ToolDefinition}s. Each spec format (OpenAPI 3 today; maybe Postman
 * collections or an MCP server's tools/list later) gets its own adapter that converts that format into
 * ToolDefinitions. Adding a format = adding one new @Component; ToolImportService does not change.
 */
public interface ToolSpecAdapter {

    /** Short name shown in the import summary, e.g. "openapi-3". */
    String formatName();

    /** True if this adapter understands the given spec text. */
    boolean supports(String specContent);

    /** Converts the spec into tool definitions (plus operations that could not be converted). */
    AdaptedSpec adapt(String specContent);
}
