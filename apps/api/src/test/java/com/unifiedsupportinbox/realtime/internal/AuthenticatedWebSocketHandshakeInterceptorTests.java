package com.unifiedsupportinbox.realtime.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthenticatedWebSocketHandshakeInterceptorTests {

    private static final URI LOCAL_WEB_ORIGIN = URI.create("http://localhost:3000");

    @Test
    void onlyLocalLoopbackProxyMayUseTheConfiguredForwardedOrigin() {
        assertThat(handshake(LOCAL_WEB_ORIGIN, "127.0.0.1", "http://localhost:3000",
                "localhost:3000", "http")).isEqualTo(200);
        assertThat(handshake(URI.create("http://localhost:3000/"), "127.0.0.1",
                "http://localhost:3000", "localhost:3000", "http")).isEqualTo(200);
        assertThat(handshake(null, "127.0.0.1", "http://localhost:3000",
                "localhost:3000", "http")).isEqualTo(403);
        assertThat(handshake(LOCAL_WEB_ORIGIN, "192.0.2.1", "http://localhost:3000",
                "localhost:3000", "http")).isEqualTo(403);
        assertThat(handshake(LOCAL_WEB_ORIGIN, "127.0.0.1", "http://localhost:3000",
                "localhost:3000,evil.example.invalid", "http")).isEqualTo(403);
    }

    @Test
    void strictDirectOriginCheckStillAppliesWithoutForwardedHeaders() {
        assertThat(handshake(null, "192.0.2.1", "http://127.0.0.1:8080",
                null, null)).isEqualTo(200);
        assertThat(handshake(null, "192.0.2.1", "null", null, null)).isEqualTo(403);
    }

    private static int handshake(
            URI localPublicOrigin, String remoteAddress, String origin,
            String forwardedHost, String forwardedProto) {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/ws");
        servletRequest.setScheme("http");
        servletRequest.setServerName("127.0.0.1");
        servletRequest.setServerPort(8080);
        servletRequest.setRemoteAddr(remoteAddress);
        servletRequest.setUserPrincipal(() -> "test-user");
        servletRequest.addHeader("Origin", origin);
        if (forwardedHost != null) servletRequest.addHeader("X-Forwarded-Host", forwardedHost);
        if (forwardedProto != null) servletRequest.addHeader("X-Forwarded-Proto", forwardedProto);

        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        boolean accepted = new AuthenticatedWebSocketHandshakeInterceptor(localPublicOrigin)
                .beforeHandshake(
                        new ServletServerHttpRequest(servletRequest),
                        new ServletServerHttpResponse(servletResponse),
                        null, new HashMap<>());
        return accepted ? 200 : servletResponse.getStatus();
    }
}
