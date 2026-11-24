package com.agentgate.gateway.registry;

/**
 * FR6: READ calls only fetch data; WRITE calls change something. The blast-radius cap counts WRITE calls only.
 */
public enum AccessMode {
    READ, WRITE;

    /** Default from the HTTP method: GET = READ. Any other method, and every MCP tool, is WRITE (fail safe). */
    public static AccessMode defaultFor(ToolKind kind, String httpMethod) {
        return kind == ToolKind.REST && "GET".equals(httpMethod) ? READ : WRITE;
    }
}
