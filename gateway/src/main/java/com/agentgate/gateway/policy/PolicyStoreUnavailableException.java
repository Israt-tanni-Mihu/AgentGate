package com.agentgate.gateway.policy;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** 503 (QA4): the policy store is unreachable and this call may not run on last known policies. */
public class PolicyStoreUnavailableException extends ErrorResponseException {

    public static final String RULE = "fail-closed-policy-store";

    public PolicyStoreUnavailableException(String toolName, String why) {
        super(HttpStatus.SERVICE_UNAVAILABLE, ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "policy store unavailable, failing closed (" + why + ")"), null);
        getBody().setTitle("Policy store unavailable");
        getBody().setProperty("rule", RULE);
        getBody().setProperty("tool", toolName);
        getHeaders().set("Retry-After", "10");
    }
}
