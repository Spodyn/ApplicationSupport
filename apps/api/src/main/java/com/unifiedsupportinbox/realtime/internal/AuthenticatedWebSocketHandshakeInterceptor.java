package com.unifiedsupportinbox.realtime.internal;

import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

final class AuthenticatedWebSocketHandshakeInterceptor implements HandshakeInterceptor {

    private final URI localPublicOrigin;

    AuthenticatedWebSocketHandshakeInterceptor(URI localPublicOrigin) {
        this.localPublicOrigin = localPublicOrigin;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) {
        if (request.getURI().getRawQuery() != null) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }

        if (!isSameOrigin(request)) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }

        Principal principal = request.getPrincipal();
        if (principal == null) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        return true;
    }

    private boolean isSameOrigin(ServerHttpRequest request) {
        String origin = request.getHeaders().getOrigin();
        if (origin == null) return false;
        try {
            URI originUri = URI.create(origin);
            URI requestUri = request.getURI();
            if (!validOrigin(originUri)) return false;
            if (sameOrigin(originUri, requestUri)) return true;
            return isTrustedLocalProxyOrigin(request, originUri, requestUri);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private boolean isTrustedLocalProxyOrigin(
            ServerHttpRequest request, URI originUri, URI requestUri) {
        if (localPublicOrigin == null
                || !validOrigin(localPublicOrigin)
                || !"http".equalsIgnoreCase(requestUri.getScheme())
                || !loopbackHost(requestUri.getHost())) {
            return false;
        }
        InetSocketAddress remote = request.getRemoteAddress();
        if (remote == null || remote.getAddress() == null || !remote.getAddress().isLoopbackAddress()) {
            return false;
        }

        String forwardedHost = singleHeader(request, "X-Forwarded-Host");
        String forwardedProto = singleHeader(request, "X-Forwarded-Proto");
        if (forwardedHost == null || !("http".equals(forwardedProto) || "https".equals(forwardedProto))) {
            return false;
        }
        URI forwardedOrigin = URI.create(forwardedProto + "://" + forwardedHost);
        return validOrigin(forwardedOrigin)
                && sameOrigin(forwardedOrigin, localPublicOrigin)
                && sameOrigin(originUri, forwardedOrigin);
    }

    private static String singleHeader(ServerHttpRequest request, String name) {
        List<String> values = request.getHeaders().get(name);
        if (values == null || values.size() != 1) return null;
        String value = values.getFirst();
        return value.isEmpty() || !value.equals(value.trim()) || value.contains(",") ? null : value;
    }

    private static boolean validOrigin(URI uri) {
        return uri.getHost() != null
                && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                && uri.getUserInfo() == null
                && uri.getRawQuery() == null
                && uri.getRawFragment() == null
                && (uri.getRawPath() == null || uri.getRawPath().isEmpty());
    }

    private static boolean sameOrigin(URI left, URI right) {
        return right.getScheme() != null
                && right.getHost() != null
                && left.getScheme().equalsIgnoreCase(right.getScheme())
                && left.getHost().equalsIgnoreCase(right.getHost())
                && effectivePort(left) == effectivePort(right);
    }

    private static boolean loopbackHost(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) || "wss".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception) {
        // No connection state is persisted. Spring owns the WebSocket lifecycle.
    }
}
