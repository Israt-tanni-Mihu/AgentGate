package com.agentgate.gateway.admin;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** 404: a kill switch was set for an agent or tool that does not exist (a typo would otherwise silently do nothing). */
public class KillSwitchTargetNotFoundException extends ErrorResponseException {

    public KillSwitchTargetNotFoundException(KillSwitchScope scope, String target) {
        super(HttpStatus.NOT_FOUND,
                ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                        scope + " '" + target + "' does not exist"),
                null);
        getBody().setTitle("Kill switch target not found");
    }
}
