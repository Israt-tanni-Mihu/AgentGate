package com.agentgate.gateway.limits;

import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** 429 Problem Details: which limit was hit, how many calls it allows, and when it resets (also in Retry-After). */
public class LimitExceededException extends ErrorResponseException {

    private final long retryAfterSeconds;

    public LimitExceededException(String limitId, int max, String toolName, Instant resetsAt, Instant now) {
        super(HttpStatus.TOO_MANY_REQUESTS,
                ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                        "Limit '" + limitId + "' reached (max " + max + " calls). Try again after " + resetsAt),
                null);
        this.retryAfterSeconds = Math.max(1, Duration.between(now, resetsAt).toSeconds());
        getBody().setTitle("Too many calls");
        getBody().setProperty("limit", limitId);
        getBody().setProperty("max", max);
        getBody().setProperty("tool", toolName);
        getBody().setProperty("resetsAt", resetsAt);
        getBody().setProperty("retryAfterSeconds", retryAfterSeconds);
    }

    @Override
    public HttpHeaders getHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        return headers;
    }
}
