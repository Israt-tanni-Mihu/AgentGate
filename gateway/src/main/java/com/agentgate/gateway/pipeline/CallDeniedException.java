package com.agentgate.gateway.pipeline;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** 403 Problem Details that tells the agent WHICH rule blocked it (FR3: the decision always names its rule). */
public class CallDeniedException extends ErrorResponseException {

    public CallDeniedException(String toolName, CallOutcome.Denied denied) {
        super(HttpStatus.FORBIDDEN, ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, denied.reason()), null);
        getBody().setTitle("Call denied");
        getBody().setProperty("tool", toolName);
        getBody().setProperty("rule", denied.rule());
        denied.details().forEach(getBody()::setProperty);
    }
}
