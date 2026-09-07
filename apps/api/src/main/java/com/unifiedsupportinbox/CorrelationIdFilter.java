package com.unifiedsupportinbox;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/** Establishes one safe correlation identifier for each HTTP request. */
public final class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-ID";
    private static final Pattern SAFE_VALUE = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String correlationId = normalize(request.getHeader(HEADER));
        request.setAttribute(ApiProblemFactory.CORRELATION_ID_ATTRIBUTE, correlationId);
        response.setHeader(HEADER, correlationId);
        try (MDC.MDCCloseable ignored = MDC.putCloseable("correlationId", correlationId)) {
            filterChain.doFilter(request, response);
        }
    }

    private static String normalize(String supplied) {
        return supplied != null && SAFE_VALUE.matcher(supplied).matches()
                ? supplied
                : UUID.randomUUID().toString();
    }
}
