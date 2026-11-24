package com.agentgate.gateway.registry.importer;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** Import failed as a whole (spec could not be downloaded, parsed or recognised). Becomes a Problem Details response. */
public class SpecImportException extends ErrorResponseException {

    public SpecImportException(HttpStatus status, String detail) {
        super(status, ProblemDetail.forStatusAndDetail(status, detail), null);
        getBody().setTitle("Tool spec import failed");
    }
}
