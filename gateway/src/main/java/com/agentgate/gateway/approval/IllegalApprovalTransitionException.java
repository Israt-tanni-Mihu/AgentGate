package com.agentgate.gateway.approval;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** 409: e.g. approving a request that already expired. */
public class IllegalApprovalTransitionException extends ErrorResponseException {

    public IllegalApprovalTransitionException(ApprovalState current, String action) {
        super(HttpStatus.CONFLICT, ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "Cannot " + action + " an approval request that is " + current), null);
        getBody().setTitle("Illegal approval transition");
        getBody().setProperty("state", current);
    }
}
