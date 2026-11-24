package com.agentgate.gateway.pipeline;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class ToolNotForwardableException extends ErrorResponseException {

    public ToolNotForwardableException(String toolName) {
        super(HttpStatus.NOT_IMPLEMENTED, ProblemDetail.forStatusAndDetail(HttpStatus.NOT_IMPLEMENTED, "Tool '" + toolName + "' is an MCP tool; forwarding to MCP servers is not implemented yet"), null);
        getBody().setTitle("Tool kind not supported yet");
    }
}
