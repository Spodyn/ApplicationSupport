package com.unifiedsupportinbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

class ApiProblemSecurityHandlersTests {

    @Test
    void authenticationEntryPointDelegatesAControlled401WithoutAuthenticationDetails() throws Exception {
        AtomicReference<Exception> captured = new AtomicReference<>();
        HandlerExceptionResolver resolver = capturingResolver(captured);
        ApiProblemAuthenticationEntryPoint entryPoint = new ApiProblemAuthenticationEntryPoint(resolver);

        entryPoint.commence(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                new BadCredentialsException("sensitive-authentication-detail"));

        assertProblem(captured.get(), ApiProblemCode.AUTHENTICATION_REQUIRED, 401);
        assertThat(captured.get().getMessage()).doesNotContain("sensitive-authentication-detail");
    }

    @Test
    void accessDeniedHandlerDelegatesAControlled403WithoutAuthorizationDetails() throws Exception {
        AtomicReference<Exception> captured = new AtomicReference<>();
        HandlerExceptionResolver resolver = capturingResolver(captured);
        ApiProblemAccessDeniedHandler handler = new ApiProblemAccessDeniedHandler(resolver);

        handler.handle(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                new AccessDeniedException("sensitive-authorization-detail"));

        assertProblem(captured.get(), ApiProblemCode.ACCESS_DENIED, 403);
        assertThat(captured.get().getMessage()).doesNotContain("sensitive-authorization-detail");
    }

    private HandlerExceptionResolver capturingResolver(AtomicReference<Exception> captured) {
        return (request, response, handler, exception) -> {
            captured.set(exception);
            return new ModelAndView();
        };
    }

    private void assertProblem(Exception exception, ApiProblemCode expectedCode, int expectedStatus) {
        assertThat(exception).isInstanceOf(ApiProblemException.class);
        ApiProblemException problem = (ApiProblemException) exception;
        assertThat(problem.code()).isEqualTo(expectedCode);
        assertThat(problem.status().value()).isEqualTo(expectedStatus);
    }
}
