package com.unifiedsupportinbox.identity.internal;

import com.unifiedsupportinbox.ApiProblemAccessDeniedHandler;
import com.unifiedsupportinbox.ApiProblemAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;

@Configuration
class BootstrapSecurityConfiguration {

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    SessionUserRefreshFilter sessionUserRefreshFilter(
            UserAccountRepository users,
            SecurityContextRepository securityContexts) {
        return new SessionUserRefreshFilter(users, securityContexts);
    }

    @Bean
    CsrfCookieExposureFilter csrfCookieExposureFilter() {
        return new CsrfCookieExposureFilter();
    }

    @Bean
    SecurityFilterChain bootstrapSecurityFilterChain(
            HttpSecurity http,
            ApiProblemAuthenticationEntryPoint authenticationEntryPoint,
            ApiProblemAccessDeniedHandler accessDeniedHandler,
            SecurityContextRepository securityContexts,
            SessionUserRefreshFilter sessionUserRefreshFilter,
            CsrfCookieExposureFilter csrfCookieExposureFilter) throws Exception {
        return http
                .securityContext(securityContext -> securityContext
                        .securityContextRepository(securityContexts)
                        .requireExplicitSave(true))
                .csrf(csrf -> csrf.spa())
                .requestCache(requestCache -> requestCache.disable())
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers(
                                "/api/v1/auth/logout",
                                "/api/v1/auth/me").authenticated()
                        .anyRequest().denyAll())
                .logout(logout -> logout
                        .logoutUrl("/api/v1/auth/logout")
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("USI_SESSION")
                        .logoutSuccessHandler(
                                new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
                .addFilterAfter(csrfCookieExposureFilter, CsrfFilter.class)
                .addFilterAfter(sessionUserRefreshFilter, AnonymousAuthenticationFilter.class)
                .build();
    }

    /**
     * Suppresses Spring Boot's generated development password. Authentication
     * is performed by the canonical identity repository, not an in-memory user.
     */
    @Bean
    UserDetailsService bootstrapUserDetailsService() {
        return new InMemoryUserDetailsManager();
    }
}
