package com.unifiedsupportinbox;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTests {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void preservesSafeClientIdAcrossRequestResponseAndMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "request-42:worker");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenByHandler = new AtomicReference<>();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                seenByHandler.set(MDC.get("correlationId")));

        assertThat(request.getAttribute(ApiProblemFactory.CORRELATION_ID_ATTRIBUTE))
                .isEqualTo("request-42:worker");
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("request-42:worker");
        assertThat(seenByHandler).hasValue("request-42:worker");
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void replacesInvalidOrOversizedClientIdsWithSafeGeneratedValues() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "x".repeat(129));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> { });

        assertThat(response.getHeader(CorrelationIdFilter.HEADER))
                .matches("[0-9a-f-]{36}");
        assertThat(request.getAttribute(ApiProblemFactory.CORRELATION_ID_ATTRIBUTE))
                .isEqualTo(response.getHeader(CorrelationIdFilter.HEADER));
    }
}
