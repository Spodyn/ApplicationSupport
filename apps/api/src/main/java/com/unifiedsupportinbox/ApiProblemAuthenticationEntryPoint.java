package com.unifiedsupportinbox;

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;

@Component
public final class ApiProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final HandlerExceptionResolver exceptionResolver;

    public ApiProblemAuthenticationEntryPoint(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
        this.exceptionResolver = exceptionResolver;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authenticationException) throws IOException, ServletException {
        exceptionResolver.resolveException(
                request,
                response,
                null,
                ApiProblemException.authenticationRequired());
    }
}
