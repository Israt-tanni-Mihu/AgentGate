package com.agentgate.mocktools.mcp;

import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * A tiny downstream MCP server (served on /mcp by the Spring AI MCP server starter) with ONE read-only tool.
 * AgentGate registers this server (FR1), imports the tool and calls it with MCP tools/call.
 * The parcel data is fake and fixed, so demos are repeatable.
 */
@Component
public class ParcelMcpTools {

    private static final Map<String, String> PARCELS = Map.of(
            "BD1001", "Delivered to Dhaka on 2026-11-20",
            "BD1002", "Out for delivery in Chattogram",
            "BD1003", "At sorting hub in Sylhet");

    @McpTool(name = "track_parcel",
            description = "Read-only. Returns the delivery status of a parcel by its tracking number (like BD1001).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public Map<String, String> trackParcel(
            @McpToolParam(description = "Tracking number, e.g. BD1001", required = true) String trackingNumber) {
        String status = PARCELS.getOrDefault(trackingNumber, "No parcel with this tracking number");
        return Map.of("trackingNumber", trackingNumber, "status", status);
    }
}
