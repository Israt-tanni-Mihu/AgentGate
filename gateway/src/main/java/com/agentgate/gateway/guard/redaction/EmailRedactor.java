package com.agentgate.gateway.guard.redaction;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Masks e-mail addresses, keeping the first letter and the domain: rahim@example.com → r***@example.com */
@Component
public class EmailRedactor implements DataRedactor {

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    @Override
    public SensitiveDataType type() {
        return SensitiveDataType.EMAIL;
    }

    @Override
    public String redact(String text) {
        return EMAIL.matcher(text).replaceAll(match -> mask(match.group()));
    }

    private static String mask(String email) {
        int at = email.indexOf('@');
        return email.charAt(0) + "***" + email.substring(at);
    }
}
