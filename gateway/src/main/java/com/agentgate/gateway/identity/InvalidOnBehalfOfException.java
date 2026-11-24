package com.agentgate.gateway.identity;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class InvalidOnBehalfOfException extends ErrorResponseException {

    public InvalidOnBehalfOfException(String detail) {
        super(HttpStatus.BAD_REQUEST, ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail), null);
        getBody().setTitle("Invalid X-On-Behalf-Of header");
    }
}
