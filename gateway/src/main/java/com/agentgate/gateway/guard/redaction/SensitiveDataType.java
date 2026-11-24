package com.agentgate.gateway.guard.redaction;

/**
 * The kinds of personal data FR5 can hide. The declaration order is the order in which redactors run:
 * emails first, so a phone-like part of an email address is already masked by the time phones are checked.
 */
public enum SensitiveDataType {
    EMAIL,
    PHONE,
    NATIONAL_ID
}
