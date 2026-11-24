package com.agentgate.gateway.guard;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** 400 Problem Details listing every way the arguments break the tool's schema. */
public class SchemaViolationException extends ErrorResponseException {

    public SchemaViolationException(String toolName, List<String> problems) {
        super(HttpStatus.BAD_REQUEST,
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                        "The arguments do not match the schema of tool '" + toolName + "'"), null);
        getBody().setTitle("Invalid arguments");
        getBody().setProperty("tool", toolName);
        getBody().setProperty("problems", problems);
    }
}
