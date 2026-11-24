package com.agentgate.gateway.guard.redaction;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The masks themselves, without Spring: fast and easy to read. */
class RedactorsTest {

    private final EmailRedactor email = new EmailRedactor();
    private final PhoneRedactor phone = new PhoneRedactor();
    private final NationalIdRedactor nationalId = new NationalIdRedactor();

    @Test
    void emailKeepsFirstLetterAndDomain() {
        assertThat(email.redact("rahim@example.com")).isEqualTo("r***@example.com");
        assertThat(email.redact("mail r.s+x@mail.bd.org now")).isEqualTo("mail r***@mail.bd.org now");
    }

    @Test
    void phoneInBothFormsIsMaskedToTheSameHint() {
        assertThat(phone.redact("01712345678")).isEqualTo("017******78");
        assertThat(phone.redact("+8801712345678")).isEqualTo("017******78");
        assertThat(phone.redact("call 01912345678 today")).isEqualTo("call 019******78 today");
    }

    @Test
    void numbersThatAreNotPhonesAreLeftAlone() {
        assertThat(phone.redact("01712345678901")).isEqualTo("01712345678901"); // 14 digits, not a phone
        assertThat(phone.redact("order 1001 total 1450")).isEqualTo("order 1001 total 1450");
    }

    @Test
    void nationalIdKeepsFirstAndLastTwoDigits() {
        assertThat(nationalId.redact("1234567890")).isEqualTo("12******90");       // 10 digits
        assertThat(nationalId.redact("1234567890123")).isEqualTo("12*********23"); // 13 digits
    }

    @Test
    void nineOrTwelveDigitsAreNotNationalIds() {
        assertThat(nationalId.redact("123456789")).isEqualTo("123456789");
        assertThat(nationalId.redact("123456789012")).isEqualTo("123456789012");
    }
}
