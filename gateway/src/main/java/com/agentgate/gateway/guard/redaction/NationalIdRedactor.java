package com.agentgate.gateway.guard.redaction;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Masks Bangladeshi national ID numbers (10, 13 or 17 digits), keeping the first and last 2 digits:
 * 1234567890123 → 12*********23
 */
@Component
public class NationalIdRedactor implements DataRedactor {

    private static final Pattern NATIONAL_ID = Pattern.compile("(?<!\\d)(?:\\d{17}|\\d{13}|\\d{10})(?!\\d)");

    @Override
    public SensitiveDataType type() {
        return SensitiveDataType.NATIONAL_ID;
    }

    @Override
    public String redact(String text) {
        return NATIONAL_ID.matcher(text).replaceAll(match -> mask(match.group()));
    }

    private static String mask(String id) {
        return id.substring(0, 2) + "*".repeat(id.length() - 4) + id.substring(id.length() - 2);
    }
}
