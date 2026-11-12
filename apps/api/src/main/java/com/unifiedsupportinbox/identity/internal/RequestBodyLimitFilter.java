package com.unifiedsupportinbox.identity.internal;

import com.unifiedsupportinbox.ApiProblemCode;
import com.unifiedsupportinbox.ApiProblemFactory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/** Bound raw JSON before Jackson/signature work, including chunked requests without Content-Length. */
final class RequestBodyLimitFilter extends OncePerRequestFilter {
    private final ApiProblemFactory problems;
    private final ObjectMapper mapper;
    RequestBodyLimitFilter(ApiProblemFactory problems, ObjectMapper mapper) {
        this.problems = problems;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/api/")
                || "GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod())
                || (request.getContentType() != null && request.getContentType().toLowerCase(Locale.ROOT).startsWith("multipart/"))) {
            chain.doFilter(request, response);
            return;
        }
        String path = request.getRequestURI();
        int limit = 2 * 1024 * 1024;
        if (path.startsWith("/api/v1/auth/")) limit = 8192;
        if (path.startsWith("/api/v1/direct-messages/")) limit = 32 * 1024;
        if (request.getContentLengthLong() > limit) {
            reject(request, response);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(limit + 1);
        if (body.length > limit) {
            reject(request, response);
            return;
        }
        ByteArrayInputStream bytes = new ByteArrayInputStream(body);
        ServletInputStream input = new ServletInputStream() {
            @Override public int read() { return bytes.read(); }
            @Override public int read(byte[] buffer, int offset, int length) { return bytes.read(buffer, offset, length); }
            @Override public boolean isFinished() { return bytes.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException("Asynchronous body reads are not supported for buffered API requests.");
            }
        };
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() { return input; }
            @Override public BufferedReader getReader() {
                return new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            }
        }, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(413);
        response.setContentType("application/problem+json");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(mapper.writeValueAsString(problems.create(HttpStatus.CONTENT_TOO_LARGE,
                ApiProblemCode.VALIDATION_FAILED, "Request too large", "The request body exceeds the allowed size.", request, List.of())));
    }
}
