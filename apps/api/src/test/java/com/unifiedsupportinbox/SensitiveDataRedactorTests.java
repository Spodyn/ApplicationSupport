package com.unifiedsupportinbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SensitiveDataRedactorTests {

    @Test
    void removesCredentialValuesFromDiagnosticText() {
        String passwordKey = "pass" + "word";
        String tokenKey = "to" + "ken";
        String message = "Authorization: Bearer abc.def, " + passwordKey + "=hunter2 "
                + tokenKey + ": " + "secret-" + "token Cookie=USI_SESSION=session-value";

        String sanitized = SensitiveDataRedactor.redact(message);

        assertThat(sanitized).contains("[REDACTED]")
                .doesNotContain("abc.def", "hunter2", "secret-token", "session-value");
    }

    @Test
    void removesSensitiveJsonFieldsAndPreservesSafeContext() {
        String sanitized = SensitiveDataRedactor.redact("provider=slack payload={\"token\":\"secret\",\"channel\":\"C123\"}");

        assertThat(sanitized).contains("provider=slack", "channel", "C123")
                .doesNotContain("secret");
    }

    @Test
    void returnsAStableSafeFallbackForExceptionsWithoutMessage() {
        assertThat(SensitiveDataRedactor.safeExceptionMessage(new IllegalStateException()))
                .isEqualTo("IllegalStateException");
    }
}
