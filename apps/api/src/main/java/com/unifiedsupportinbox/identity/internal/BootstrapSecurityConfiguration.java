package com.unifiedsupportinbox.identity.internal;

import com.unifiedsupportinbox.ApiProblemAccessDeniedHandler;
import com.unifiedsupportinbox.ApiProblemAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Temporary deny-by-default security boundary until the identity module adds
 * the authenticated API and authorization rules.
 */
@Configuration
class BootstrapSecurityConfiguration {

    @Bean
    SecurityFilterChain bootstrapSecurityFilterChain(
            HttpSecurity http,
            ApiProblemAuthenticationEntryPoint authenticationEntryPoint,
            ApiProblemAccessDeniedHandler accessDeniedHandler) throws Exception {
        return http
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
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
