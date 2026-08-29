package com.unifiedsupportinbox.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class CredentialHashConfigurationTests {

    private final PasswordEncoder encoder = new CredentialHashConfiguration().accountEncoder();

    @Test
    void argon2IdUsesRandomSaltsAndVerifiesOnlyTheCorrectValue() {
        String raw = testValue();
        String firstHash = encoder.encode(raw);
        String secondHash = encoder.encode(raw);

        assertThat(firstHash).startsWith("$argon2id$");
        assertThat(secondHash).startsWith("$argon2id$");
        assertThat(secondHash).isNotEqualTo(firstHash);
        assertThat(encoder.matches(raw, firstHash)).isTrue();
        assertThat(encoder.matches(testWrongValue(), firstHash)).isFalse();
        assertThat(encoder.upgradeEncoding(firstHash)).isFalse();
    }

    @Test
    void weakerArgon2IdCostParametersAreMarkedForUpgrade() {
        PasswordEncoder legacyEncoder = new Argon2CredentialEncoder(32_768, 2, 1, 32);
        String raw = testValue();
        String oldHash = legacyEncoder.encode(raw);

        assertThat(encoder.matches(raw, oldHash)).isTrue();
        assertThat(encoder.upgradeEncoding(oldHash)).isTrue();
    }

    @Test
    void strongerArgon2IdCostParametersAreNeverDowngraded() {
        PasswordEncoder strongerEncoder = new Argon2CredentialEncoder(131_072, 4, 4, 32);
        String strongerHash = strongerEncoder.encode(testValue());

        assertThat(encoder.matches(testValue(), strongerHash)).isTrue();
        assertThat(encoder.upgradeEncoding(strongerHash)).isFalse();
    }

    private static String testValue() {
        return String.join("-", "test", "only", "hash", "candidate");
    }

    private static String testWrongValue() {
        return String.join("-", "test", "only", "wrong", "candidate");
    }
}
