package com.unifiedsupportinbox.realtime.internal;

import java.net.URI;
import java.security.Principal;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

final class AuthenticatedWebSocketHandshakeInterceptor implements HandshakeInterceptor {

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

    private static boolean isSameOrigin(ServerHttpRequest request) {
        String origin = request.getHeaders().getOrigin();
        if (origin == null) return false;
        try {
            URI originUri = URI.create(origin);
            URI requestUri = request.getURI();
            return originUri.getUserInfo() == null
                    && originUri.getQuery() == null
                    && originUri.getFragment() == null
                    && originUri.getPath().isEmpty()
                    && originUri.getScheme().equalsIgnoreCase(requestUri.getScheme())
                    && originUri.getHost().equalsIgnoreCase(requestUri.getHost())
                    && effectivePort(originUri) == effectivePort(requestUri);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
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
