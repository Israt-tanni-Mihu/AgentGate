package com.agentgate.gateway.registry;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** Tool names are unique (agents call tools by name), so a second tool with the same name is a 409 Conflict. */
public class DuplicateToolNameException extends ErrorResponseException {

    public DuplicateToolNameException(String name) {
        super(HttpStatus.CONFLICT,
                ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "A tool named '" + name + "' already exists"),
                null);
        getBody().setTitle("Duplicate tool name");
    }
}
