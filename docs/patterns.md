# Design patterns in AgentGate

Rule: every pattern here is tied to a requirement. A pattern without a requirement does not belong in this table.

| Pattern | Requirement it serves | Participating classes | Alternative rejected (and why) |
|---|---|---|---|
| **Adapter** | FR1 — import REST tools from an OpenAPI file; QA5 — a classmate adds a new tool with no code changes. The import must stay open to other spec formats (e.g. Postman collection, an MCP server's `tools/list`). | Target: `registry.importer.ToolSpecAdapter` · Adapter: `OpenApiToolSpecAdapter` (OpenAPI 3 JSON → `ToolDefinition`) · Client: `ToolImportService` (picks the first adapter whose `supports()` is true, upserts by name) · Format-neutral data: `ToolDefinition`, `AdaptedSpec` | **Parse OpenAPI directly inside `ToolImportService`** — simpler today, but every new format would mean editing the import/upsert logic (violates Open/Closed). **swagger-parser library** — full spec support, but a heavy dependency for the subset we need (operationId, parameters, JSON body, local `$ref`); can be put *behind* the same interface later without touching the client. |
