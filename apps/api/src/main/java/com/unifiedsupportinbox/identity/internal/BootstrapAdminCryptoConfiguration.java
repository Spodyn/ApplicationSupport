package com.unifiedsupportinbox.identity.internal;

import com.password4j.Argon2Function;
import com.password4j.types.Argon2;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password4j.Argon2Password4jPasswordEncoder;

@Configuration(proxyBeanMethods = false)
class BootstrapAdminCryptoConfiguration {

    @Bean("bootstrapAdminPasswordEncoder")
    PasswordEncoder bootstrapAdminPasswordEncoder() {
        // Bootstrap-only baseline. USI-65 owns reusable credential tuning and rehash policy.
        Argon2Function argon2Id = Argon2Function.getInstance(65_536, 3, 2, 32, Argon2.ID);
        return new Argon2Password4jPasswordEncoder(argon2Id);
    }
}
