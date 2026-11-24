package com.agentgate.gateway.pipeline;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class InvalidArgumentsException extends ErrorResponseException {

    public InvalidArgumentsException(String detail) {
        super(HttpStatus.BAD_REQUEST, ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail), null);
        getBody().setTitle("Invalid arguments");
    }
}
