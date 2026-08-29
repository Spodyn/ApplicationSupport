package com.unifiedsupportinbox.identity.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

final class CsrfCookieExposureFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Object tokenAttribute = request.getAttribute(CsrfToken.class.getName());
        if (tokenAttribute instanceof CsrfToken csrfToken) {
            csrfToken.getToken();
        }
        filterChain.doFilter(request, response);
    }
}
