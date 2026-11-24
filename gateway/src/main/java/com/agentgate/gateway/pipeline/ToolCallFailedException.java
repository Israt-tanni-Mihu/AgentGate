package com.agentgate.gateway.pipeline;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class ToolCallFailedException extends ErrorResponseException {

    public ToolCallFailedException(String toolName, String why) {
        super(HttpStatus.BAD_GATEWAY, ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "Calling tool '" + toolName + "' failed: " + why), null);
        getBody().setTitle("Tool call failed");
    }
}
