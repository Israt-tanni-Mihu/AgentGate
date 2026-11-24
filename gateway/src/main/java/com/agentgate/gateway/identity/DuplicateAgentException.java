package com.agentgate.gateway.identity;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class DuplicateAgentException extends ErrorResponseException {

    public DuplicateAgentException(String clientId) {
        super(HttpStatus.CONFLICT,
                ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "An agent '" + clientId + "' already exists"),
                null);
        getBody().setTitle("Duplicate agent");
    }
}
