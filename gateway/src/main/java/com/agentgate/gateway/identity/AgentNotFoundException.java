package com.agentgate.gateway.identity;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class AgentNotFoundException extends ErrorResponseException {

    public AgentNotFoundException(String clientId) {
        super(HttpStatus.NOT_FOUND,
                ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Agent '" + clientId + "' not found"),
                null);
        getBody().setTitle("Agent not found");
    }
}
