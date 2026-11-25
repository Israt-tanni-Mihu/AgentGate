package com.agentgate.gateway.registry;

import com.agentgate.gateway.registry.importer.ImportRequest;
import com.agentgate.gateway.registry.importer.ToolImportService;
import com.agentgate.gateway.registry.mcp.McpServerRequest;
import com.agentgate.gateway.registry.mcp.McpServerResponse;
import com.agentgate.gateway.registry.mcp.McpServerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * "docker" profile only (C1): on the first start with an empty registry, imports the ShopBD REST tools
 * (OpenAPI) and the parcel MCP tools from mock-tools, so a fresh clone has a working demo.
 * Failure is only logged: the gateway must still start, and an admin can import by hand later.
 */
@Component
@Profile("docker")
@EnableConfigurationProperties(DemoToolProperties.class)
class DemoToolSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoToolSeeder.class);

    private final ToolRepository tools;
    private final ToolImportService importService;
    private final McpServerService mcpServers;
    private final DemoToolProperties properties;

    DemoToolSeeder(ToolRepository tools, ToolImportService importService, McpServerService mcpServers,
                   DemoToolProperties properties) {
        this.tools = tools;
        this.importService = importService;
        this.mcpServers = mcpServers;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (tools.count() > 0) {
            return;
        }
        String base = properties.baseUrl();
        try {
            importService.importFromUrl(new ImportRequest(base + "/v3/api-docs", "shop-team", base));
            McpServerResponse server = mcpServers.register(
                    new McpServerRequest("shop-parcels", base + "/mcp", "logistics-team", RiskLevel.LOW));
            mcpServers.importTools(server.id());
            log.info("Seeded demo tools from {} ({} tools in the registry)", base, tools.count());
        } catch (RuntimeException e) {
            log.warn("Could not seed demo tools from {}: {}. Import them by hand (POST /api/admin/tools/import).",
                    base, e.getMessage());
        }
    }
}
