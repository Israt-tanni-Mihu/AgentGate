package com.agentgate.mocktools.shop;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Thrown when a customer or order id does not exist. Spring turns it into a 404 Problem Details response. */
public class NotFoundException extends ResponseStatusException {

    public NotFoundException(String what, long id) {
        super(HttpStatus.NOT_FOUND, what + " " + id + " not found");
    }
}
