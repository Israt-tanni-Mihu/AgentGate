package com.agentgate.gateway.policy;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class PolicyVersionNotFoundException extends ErrorResponseException {

    public PolicyVersionNotFoundException(int version) {
        super(HttpStatus.NOT_FOUND,
                ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Policy version " + version + " not found"),
                null);
        getBody().setTitle("Policy version not found");
    }
}
