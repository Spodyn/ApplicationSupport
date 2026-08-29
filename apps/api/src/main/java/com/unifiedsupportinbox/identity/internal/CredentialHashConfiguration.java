package com.unifiedsupportinbox.identity.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
class CredentialHashConfiguration {

    private static final int MEMORY_KIB = 65_536;
    private static final int ITERATIONS = 3;
    private static final int PARALLELISM = 2;
    private static final int HASH_LENGTH_BYTES = 32;

    @Bean(name = {"accountEncoder", "bootstrapAdminPasswordEncoder"})
    PasswordEncoder accountEncoder() {
        return new Argon2CredentialEncoder(
                MEMORY_KIB,
                ITERATIONS,
                PARALLELISM,
                HASH_LENGTH_BYTES);
    }
}
