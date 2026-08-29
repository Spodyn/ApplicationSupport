package com.unifiedsupportinbox.identity.internal;

import com.password4j.Argon2Function;
import com.password4j.types.Argon2;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password4j.Argon2Password4jPasswordEncoder;

final class Argon2CredentialEncoder implements PasswordEncoder {

    private static final int CURRENT_ARGON2_VERSION = Argon2Function.ARGON2_VERSION_13;

    private final PasswordEncoder delegate;
    private final int memoryKib;
    private final int iterations;
    private final int parallelism;
    private final int hashLengthBytes;

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
        this.hashLengthBytes = hashLengthBytes;
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
        if (rawPassword == null || encodedPassword == null || encodedPassword.isBlank()) {
            return false;
        }

        try {
            Argon2Function storedFunction = Argon2Function.getInstanceFromHash(encodedPassword);
            return storedFunction.check(rawPassword, encodedPassword);
        } catch (RuntimeException malformedEncoding) {
            return false;
        }
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) {
        if (encodedPassword == null || encodedPassword.isBlank()) {
            return false;
        }

        try {
            Argon2Function storedFunction = Argon2Function.getInstanceFromHash(encodedPassword);
            if (storedFunction.getVariant() != Argon2.ID) {
                return true;
            }
            if (storedFunction.getVersion() > CURRENT_ARGON2_VERSION) {
                return false;
            }

            return storedFunction.getVersion() < CURRENT_ARGON2_VERSION
                    || storedFunction.getMemory() < memoryKib
                    || storedFunction.getIterations() < iterations
                    || storedFunction.getParallelism() < parallelism
                    || storedFunction.getOutputLength() < hashLengthBytes;
        } catch (RuntimeException malformedEncoding) {
            return true;
        }
    }
}
