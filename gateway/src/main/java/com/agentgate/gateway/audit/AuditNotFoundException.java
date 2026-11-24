package com.agentgate.gateway.audit;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class AuditNotFoundException extends ErrorResponseException {

    public AuditNotFoundException(UUID callId) {
        super(HttpStatus.NOT_FOUND,
                ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "No audit record for call " + callId), null);
        getBody().setTitle("Audit record not found");
    }
}
