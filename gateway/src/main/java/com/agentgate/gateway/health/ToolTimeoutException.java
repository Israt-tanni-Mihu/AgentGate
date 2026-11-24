package com.agentgate.gateway.health;

import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** 504: the tool did not answer within its timeout. */
public class ToolTimeoutException extends ErrorResponseException {

    public static final String RULE = "tool-timeout";

    public ToolTimeoutException(String toolName, Duration timeout, boolean write) {
        super(HttpStatus.GATEWAY_TIMEOUT, ProblemDetail.forStatusAndDetail(HttpStatus.GATEWAY_TIMEOUT,
                "Tool '" + toolName + "' did not answer within " + timeout.toMillis() + " ms."
                        + (write ? " This is a write tool: the action may or may not have happened, so do not repeat it blindly."
                                 : "")), null);
        getBody().setTitle("Tool timed out");
        getBody().setProperty("rule", RULE);
    }
}
