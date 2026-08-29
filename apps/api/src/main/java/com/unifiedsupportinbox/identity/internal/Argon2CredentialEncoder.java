package com.unifiedsupportinbox.identity.internal;

import com.password4j.Argon2Function;
import com.password4j.types.Argon2;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password4j.Argon2Password4jPasswordEncoder;

final class Argon2CredentialEncoder implements PasswordEncoder {

    private static final int CURRENT_ARGON2_VERSION = 19;
    private static final Pattern PHC_PATTERN = Pattern.compile(
            "^\\$argon2id\\$v=(\\d+)\\$m=(\\d+),t=(\\d+),p=(\\d+)\\$[^$]+\\$[^$]+$");

    private final PasswordEncoder delegate;
    private final int memoryKib;
    private final int iterations;
    private final int parallelism;

    Argon2CredentialEncoder(
            int memoryKib,
            int iterations,
            int parallelism,
            int hashLengthBytes) {
        if (memoryKib <= 0 || iterations <= 0 || parallelism <= 0 || hashLengthBytes <= 0) {
            throw new IllegalArgumentException("Argon2 parameters must be positive");
        }
        this.memoryKib = memoryKib;
        this.iterations = iterations;
        this.parallelism = parallelism;
        this.delegate = new Argon2Password4jPasswordEncoder(
                Argon2Function.getInstance(
                        memoryKib,
                        iterations,
                        parallelism,
                        hashLengthBytes,
                        Argon2.ID));
    }

    @Override
    public String encode(CharSequence rawPassword) {
        return delegate.encode(rawPassword);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        return delegate.matches(rawPassword, encodedPassword);
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        if (encodedPassword == null || encodedPassword.isBlank()) {
            return false;
        }

        Matcher matcher = PHC_PATTERN.matcher(encodedPassword);
        if (!matcher.matches()) {
            return true;
        }

        try {
            int version = Integer.parseInt(matcher.group(1));
            if (version > CURRENT_ARGON2_VERSION) {
                return false;
            }
            int storedMemory = Integer.parseInt(matcher.group(2));
            int storedIterations = Integer.parseInt(matcher.group(3));
            int storedParallelism = Integer.parseInt(matcher.group(4));

            return version < CURRENT_ARGON2_VERSION
                    || storedMemory < memoryKib
                    || storedIterations < iterations
                    || storedParallelism < parallelism;
        } catch (NumberFormatException invalidEncoding) {
            return true;
        }
    }
}
