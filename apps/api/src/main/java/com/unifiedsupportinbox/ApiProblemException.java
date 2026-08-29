package com.unifiedsupportinbox;

import org.springframework.http.HttpStatus;

/**
 * Controlled application exception whose public detail is explicitly chosen by
 * application code instead of being derived from an arbitrary exception message.
 */
public final class ApiProblemException extends RuntimeException {

    private final ApiProblemCode code;
    private final HttpStatus status;
    private final String title;

    private ApiProblemException(
            ApiProblemCode code,
            HttpStatus status,
            String title,
            String detail,
            Throwable cause) {
        super(detail, cause);
        this.code = code;
        this.status = status;
        this.title = title;
    }

    public static ApiProblemException authenticationRequired() {
        return new ApiProblemException(
                ApiProblemCode.AUTHENTICATION_REQUIRED,
                HttpStatus.UNAUTHORIZED,
                "Authentication required",
                "Authentication is required to access this resource.",
                null);
    }

    public static ApiProblemException invalidCredentials() {
        return new ApiProblemException(
                ApiProblemCode.AUTHENTICATION_REQUIRED,
                HttpStatus.UNAUTHORIZED,
                "Authentication failed",
                "Invalid email or password.",
                null);
    }

    public static ApiProblemException accessDenied() {
        return new ApiProblemException(
                ApiProblemCode.ACCESS_DENIED,
                HttpStatus.FORBIDDEN,
                "Access denied",
                "You do not have permission to perform this action.",
                null);
    }

    public static ApiProblemException notFound(String safeDetail) {
        return new ApiProblemException(
                ApiProblemCode.RESOURCE_NOT_FOUND,
                HttpStatus.NOT_FOUND,
                "Resource not found",
                safeDetail(safeDetail, "The requested resource was not found."),
                null);
    }

    public static ApiProblemException conflict(String safeDetail) {
        return new ApiProblemException(
                ApiProblemCode.CONFLICT,
                HttpStatus.CONFLICT,
                "Conflict",
                safeDetail(safeDetail, "The request conflicts with the current resource state."),
                null);
    }

    public static ApiProblemException rateLimited(String safeDetail) {
        return new ApiProblemException(
                ApiProblemCode.RATE_LIMITED,
                HttpStatus.TOO_MANY_REQUESTS,
                "Too many requests",
                safeDetail(safeDetail, "Too many requests were made. Try again later."),
                null);
    }

    public static ApiProblemException providerFailure(Throwable cause) {
        return new ApiProblemException(
                ApiProblemCode.PROVIDER_FAILURE,
                HttpStatus.BAD_GATEWAY,
                "Provider failure",
                "A provider request failed.",
                cause);
    }

    public static ApiProblemException applicationFailure(Throwable cause) {
        return new ApiProblemException(
                ApiProblemCode.APPLICATION_FAILURE,
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Application failure",
                "The request could not be completed.",
                cause);
    }

    public ApiProblemCode code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    private static String safeDetail(String detail, String fallback) {
        return detail == null || detail.isBlank() ? fallback : detail;
    }
}
