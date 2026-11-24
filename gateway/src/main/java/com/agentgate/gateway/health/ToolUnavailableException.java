package com.agentgate.gateway.health;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** 503: the circuit breaker of this tool is OPEN, so the call is refused at once without calling the tool. */
public class ToolUnavailableException extends ErrorResponseException {

    public static final String RULE = "circuit-open";

    public ToolUnavailableException(String toolName, long retryAfterSeconds) {
        super(HttpStatus.SERVICE_UNAVAILABLE, ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Tool '" + toolName + "' is temporarily unavailable (circuit breaker open). Try again later."), null);
        getBody().setTitle("Tool temporarily unavailable");
        getBody().setProperty("rule", RULE);
        getHeaders().set("Retry-After", String.valueOf(retryAfterSeconds));
    }
}
