package com.unifiedsupportinbox.provider;

import java.time.Duration;

/**
 * Sanitized provider-transfer failure. Provider response bodies, URLs and
 * credentials must never be copied into this exception.
 */
public final class ProviderAttachmentException extends RuntimeException {

    private final FailureKind kind;
    private final String errorCode;
    private final Duration retryAfter;

    private ProviderAttachmentException(
            FailureKind kind,
            String errorCode,
            Duration retryAfter,
            Throwable cause) {
        super(kind.defaultMessage(), cause);
        this.kind = java.util.Objects.requireNonNull(kind, "kind");
        this.errorCode = normalizeCode(errorCode, kind);
        this.retryAfter = positive(retryAfter);
    }

    public static ProviderAttachmentException timeout(String errorCode, Throwable cause) {
        return new ProviderAttachmentException(FailureKind.TIMEOUT, errorCode, null, cause);
    }

    public static ProviderAttachmentException rateLimited(String errorCode, Duration retryAfter) {
        return new ProviderAttachmentException(FailureKind.RATE_LIMITED, errorCode, retryAfter, null);
    }

    public static ProviderAttachmentException authentication(String errorCode) {
        return new ProviderAttachmentException(FailureKind.AUTHENTICATION, errorCode, null, null);
    }

    public static ProviderAttachmentException notFound(String errorCode) {
        return new ProviderAttachmentException(FailureKind.NOT_FOUND, errorCode, null, null);
    }

    public static ProviderAttachmentException unsafeRedirect(String errorCode) {
        return new ProviderAttachmentException(FailureKind.UNSAFE_REDIRECT, errorCode, null, null);
    }

    public static ProviderAttachmentException validation(String errorCode) {
        return new ProviderAttachmentException(FailureKind.VALIDATION, errorCode, null, null);
    }

    public static ProviderAttachmentException unavailable(String errorCode, Throwable cause) {
        return new ProviderAttachmentException(FailureKind.PROVIDER_UNAVAILABLE, errorCode, null, cause);
    }

    public FailureKind kind() {
        return kind;
    }

    public String errorCode() {
        return errorCode;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    public boolean retryable() {
        return kind.retryable();
    }

    public enum FailureKind {
        TIMEOUT(true, "Provider attachment request timed out."),
        RATE_LIMITED(true, "Provider attachment request was rate limited."),
        PROVIDER_UNAVAILABLE(true, "Provider attachment service is unavailable."),
        AUTHENTICATION(false, "Provider attachment authorization failed."),
        NOT_FOUND(false, "Provider attachment was not found."),
        UNSAFE_REDIRECT(false, "Provider attachment redirect was rejected."),
        VALIDATION(false, "Provider attachment request was rejected.");

        private final boolean retryable;
        private final String defaultMessage;

        FailureKind(boolean retryable, String defaultMessage) {
            this.retryable = retryable;
            this.defaultMessage = defaultMessage;
        }

        boolean retryable() {
            return retryable;
        }

        String defaultMessage() {
            return defaultMessage;
        }
    }

    private static String normalizeCode(String value, FailureKind fallback) {
        if (value == null || value.isBlank()) return fallback.name();
        String normalized = value.strip().toUpperCase(java.util.Locale.ROOT)
                .replaceAll("[^A-Z0-9_]+", "_");
        if (normalized.isBlank()) return fallback.name();
        return normalized.length() <= 128 ? normalized : normalized.substring(0, 128);
    }

    private static Duration positive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative() ? value : null;
    }
}
