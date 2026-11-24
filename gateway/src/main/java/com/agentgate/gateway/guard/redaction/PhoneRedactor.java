package com.agentgate.gateway.guard.redaction;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Masks Bangladeshi mobile numbers, written as 01XXXXXXXXX or +8801XXXXXXXXX (also 8801XXXXXXXXX).
 * The mask keeps the first 3 and last 2 digits of the 11-digit form: 01712345678 → 017******78
 */
@Component
public class PhoneRedactor implements DataRedactor {

    /** Not preceded or followed by another digit, so it does not match inside a longer number. */
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?880|0)1[3-9]\\d{8}(?!\\d)");
    private static final String COUNTRY_PREFIX = "^\\+?880";

    @Override
    public SensitiveDataType type() {
        return SensitiveDataType.PHONE;
    }

    @Override
    public String redact(String text) {
        return PHONE.matcher(text).replaceAll(match -> mask(match.group()));
    }

    private static String mask(String phone) {
        String local = phone.replaceFirst(COUNTRY_PREFIX, "0"); // always the 11-digit form, 01XXXXXXXXX
        return local.substring(0, 3) + "******" + local.substring(local.length() - 2);
    }
}
