package com.agentgate.gateway.registry;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** ErrorResponseException already carries a ProblemDetail, so Spring turns it into a 404 problem+json. */
public class ToolNotFoundException extends ErrorResponseException {

    public ToolNotFoundException(Long id) {
        super(HttpStatus.NOT_FOUND,
                ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Tool with id " + id + " not found"),
                null);
        getBody().setTitle("Tool not found");
    }
}
