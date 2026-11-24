package com.agentgate.gateway.registry.mcp;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class DuplicateMcpServerException extends ErrorResponseException {

    public DuplicateMcpServerException(String name) {
        super(HttpStatus.CONFLICT,
                ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "An MCP server named '" + name + "' already exists"),
                null);
        getBody().setTitle("Duplicate MCP server name");
    }
}
