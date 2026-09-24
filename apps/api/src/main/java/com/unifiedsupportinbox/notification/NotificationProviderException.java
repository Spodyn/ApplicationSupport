package com.unifiedsupportinbox.notification;

import java.time.Duration;

/** Sanitized provider failure that the common gateway can safely classify for retry. */
public final class NotificationProviderException extends RuntimeException {

    private final Kind kind;
    private final String errorCode;
    private final Duration retryAfter;

    private NotificationProviderException(Kind kind, String errorCode, Duration retryAfter) {
        super(errorCode, null, false, false);
        this.kind = kind;
        this.errorCode = errorCode;
        this.retryAfter = retryAfter;
    }

    public static NotificationProviderException timeout() {
        return transientFailure("PROVIDER_TIMEOUT", null);
    }

    public static NotificationProviderException transientFailure(String errorCode, Duration retryAfter) {
        return new NotificationProviderException(Kind.TRANSIENT, errorCode, retryAfter);
    }

    public static NotificationProviderException permanentFailure(String errorCode) {
        return new NotificationProviderException(Kind.PERMANENT, errorCode, null);
    }

    public Kind kind() { return kind; }
    public String errorCode() { return errorCode; }
    public Duration retryAfter() { return retryAfter; }

    public enum Kind { TRANSIENT, PERMANENT }
}
