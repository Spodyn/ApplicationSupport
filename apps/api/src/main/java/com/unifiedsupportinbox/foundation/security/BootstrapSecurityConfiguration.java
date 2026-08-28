package com.unifiedsupportinbox.foundation.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Temporary deny-by-default security boundary until USI-48's successors add
 * the authenticated API and authorization rules.
 */
@Configuration
class BootstrapSecurityConfiguration {

    @Bean
    SecurityFilterChain bootstrapSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health").permitAll()
                        .anyRequest().denyAll())
                .build();
    }

    /**
     * Prevents Spring Boot from creating and logging a generated development
     * password while authentication is intentionally not implemented yet.
     */
    @Bean
    UserDetailsService bootstrapUserDetailsService() {
        return new InMemoryUserDetailsManager();
    }
}
