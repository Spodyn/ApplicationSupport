package com.unifiedsupportinbox.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PasswordPolicyTests {

    @Test
    void storagePolicyAcceptsInclusiveLengthBoundariesAndCountsUnicodeCodePoints() {
        char[] minimum = "a".repeat(PasswordPolicy.MINIMUM_CODE_POINTS).toCharArray();
        char[] maximum = "b".repeat(PasswordPolicy.MAXIMUM_CODE_POINTS).toCharArray();
        char[] supplementary = "\uD83D\uDE00".repeat(PasswordPolicy.MINIMUM_CODE_POINTS).toCharArray();

        assertThatCode(() -> PasswordPolicy.validateForStorage(minimum)).doesNotThrowAnyException();
        assertThatCode(() -> PasswordPolicy.validateForStorage(maximum)).doesNotThrowAnyException();
        assertThatCode(() -> PasswordPolicy.validateForStorage(supplementary)).doesNotThrowAnyException();
        assertThat(Character.codePointCount(supplementary, 0, supplementary.length))
                .isEqualTo(PasswordPolicy.MINIMUM_CODE_POINTS);
    }

    @Test
    void storagePolicyRejectsOutOfRangeAndMultiLineValuesWithoutEchoingInput() {
        char[] tooShort = "a".repeat(PasswordPolicy.MINIMUM_CODE_POINTS - 1).toCharArray();
        char[] tooLong = "b".repeat(PasswordPolicy.MAXIMUM_CODE_POINTS + 1).toCharArray();
        char[] multiLine = String.join("", "test-only-safe", "\n", "second-line").toCharArray();
        char[] nulDelimited = String.join("", "test-only-safe", "\0", "tail").toCharArray();

        assertThatThrownBy(() -> PasswordPolicy.validateForStorage(tooShort))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("password must contain 12 to 128 Unicode characters");
        assertThatThrownBy(() -> PasswordPolicy.validateForStorage(tooLong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("password must contain 12 to 128 Unicode characters");
        assertThatThrownBy(() -> PasswordPolicy.validateForStorage(multiLine))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("password must contain exactly one text line")
                .hasMessageNotContaining(new String(multiLine));
        assertThatThrownBy(() -> PasswordPolicy.validateForStorage(nulDelimited))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("password must contain exactly one text line")
                .hasMessageNotContaining(new String(nulDelimited));
    }

    @Test
    void authenticationAllowsLegacyShortValuesButStillBoundsAndSanitizesInput() {
        assertThat(PasswordPolicy.acceptsAuthenticationInput("short")).isTrue();
        assertThat(PasswordPolicy.acceptsAuthenticationInput("x".repeat(128))).isTrue();
        assertThat(PasswordPolicy.acceptsAuthenticationInput("x".repeat(129))).isFalse();
        assertThat(PasswordPolicy.acceptsAuthenticationInput("line\nbreak")).isFalse();
        assertThat(PasswordPolicy.acceptsAuthenticationInput("line\rbreak")).isFalse();
        assertThat(PasswordPolicy.acceptsAuthenticationInput("nul\0break")).isFalse();
        assertThat(PasswordPolicy.acceptsAuthenticationInput("")).isFalse();
        assertThat(PasswordPolicy.acceptsAuthenticationInput(null)).isFalse();
    }
}
