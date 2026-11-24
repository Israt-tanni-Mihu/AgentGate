package com.agentgate.gateway.guard.redaction;

/**
 * STRATEGY (see docs/patterns.md): one way of masking one kind of personal data.
 * {@link RedactionStep} picks the redactors a clearance needs; each redactor only knows its own data type.
 * Adding a data type = adding one class that implements this interface.
 */
public interface DataRedactor {

    SensitiveDataType type();

    /** Returns the text with every match masked. Text without matches is returned unchanged. */
    String redact(String text);
}
