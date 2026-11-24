package com.agentgate.gateway.audit;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** 400 as an RFC 9457 problem, e.g. an unknown export format or from > to. */
public class AuditBadRequestException extends ErrorResponseException {

    public AuditBadRequestException(String detail) {
        super(HttpStatus.BAD_REQUEST, ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail), null);
        getBody().setTitle("Invalid audit query");
    }
}
