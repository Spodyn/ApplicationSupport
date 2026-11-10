package com.unifiedsupportinbox.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.unifiedsupportinbox.ApiProblemFactory;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

class RequestBodyLimitFilterTests {
    private final RequestBodyLimitFilter filter = new RequestBodyLimitFilter(new ApiProblemFactory(), new ObjectMapper());

    @Test
    void boundsChunkedAuthenticationAndProviderBodiesBeforeParsing() throws Exception {
        for (String path : new String[] {"/api/v1/auth/login", "/api/v1/providers/slack/events"}) {
            int max = path.contains("auth") ? 8192 : 2 * 1024 * 1024;
            var request = new MockHttpServletRequest("POST", path) {
                @Override public long getContentLengthLong() { return -1; }
            };
            request.setContentType("application/json");
            request.setContent(new byte[max + 1]);
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> { throw new AssertionError("Oversized body reached application"); });
            assertThat(response.getStatus()).isEqualTo(413);
            assertThat(response.getContentAsString()).contains("VALIDATION_FAILED").doesNotContain("password");
        }
    }

    @Test
    void preservesExactRawWebhookBytesForSignatureVerification() throws Exception {
        byte[] bytes = "{ \"text\": \"zażółć\" }\r\n".getBytes(StandardCharsets.UTF_8);
        var request = new MockHttpServletRequest("POST", "/api/v1/providers/slack/events");
        request.setContentType("application/json");
        request.setContent(bytes);
        var seen = new AtomicReference<byte[]>();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> seen.set(req.getInputStream().readAllBytes()));
        assertThat(seen.get()).containsExactly(bytes);
    }
}
