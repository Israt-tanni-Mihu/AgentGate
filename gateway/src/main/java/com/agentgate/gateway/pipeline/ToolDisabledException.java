package com.agentgate.gateway.pipeline;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class ToolDisabledException extends ErrorResponseException {

    public ToolDisabledException(String toolName) {
        super(HttpStatus.FORBIDDEN, ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Tool '" + toolName + "' is disabled by an administrator"), null);
        getBody().setTitle("Tool disabled");
    }
}
