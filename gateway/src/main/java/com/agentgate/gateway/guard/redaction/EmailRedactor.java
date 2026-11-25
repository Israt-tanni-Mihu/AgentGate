package com.agentgate.gateway.guard.redaction;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Masks e-mail addresses, keeping the first letter and the domain: rahim@example.com → r***@example.com */
@Component
public class EmailRedactor implements DataRedactor {

    /**
     * QA1 finding: the plain "[...]+@..." pattern re-scanned the whole run of letters from every start position, so a
     * 100 000 character string took minutes and froze the audit writer thread (and a core). The lookbehind lets a
     * match start only at the beginning of a run, so each character is scanned once; domain labels are bounded.
     */
    private static final Pattern EMAIL = Pattern.compile(
            "(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9-]{1,63}(?:\\.[A-Za-z0-9-]{1,63})*\\.[A-Za-z]{2,24}");

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
