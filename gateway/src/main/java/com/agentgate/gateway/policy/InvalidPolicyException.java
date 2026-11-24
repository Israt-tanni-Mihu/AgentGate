package com.agentgate.gateway.policy;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** 400 with the list of everything wrong in the submitted rules, so the admin can fix them all at once. */
public class InvalidPolicyException extends ErrorResponseException {

    public InvalidPolicyException(List<String> problems) {
        super(HttpStatus.BAD_REQUEST,
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The policy rules are not valid"),
                null);
        getBody().setTitle("Invalid policy");
        getBody().setProperty("problems", problems);
    }
}
