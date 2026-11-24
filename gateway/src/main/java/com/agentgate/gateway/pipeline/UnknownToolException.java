package com.agentgate.gateway.pipeline;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class UnknownToolException extends ErrorResponseException {

    public UnknownToolException(String toolName) {
        super(HttpStatus.NOT_FOUND, ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "No tool named '" + toolName + "' is registered"), null);
        getBody().setTitle("Unknown tool");
    }
}
