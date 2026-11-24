package com.agentgate.gateway.registry.mcp;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class McpServerNotFoundException extends ErrorResponseException {

    public McpServerNotFoundException(Long id) {
        super(HttpStatus.NOT_FOUND,
                ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "No MCP server with id " + id), null);
        getBody().setTitle("MCP server not found");
    }
}
