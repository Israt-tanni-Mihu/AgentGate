package com.agentgate.gateway.approval;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class ApprovalNotFoundException extends ErrorResponseException {

    public ApprovalNotFoundException(UUID id) {
        super(HttpStatus.NOT_FOUND,
                ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Approval request " + id + " not found"), null);
        getBody().setTitle("Approval request not found");
    }
}
