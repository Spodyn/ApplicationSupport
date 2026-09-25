package com.unifiedsupportinbox;

import java.util.regex.Pattern;

/** Sanitizes untrusted diagnostic text before it crosses a logging boundary. */
public final class SensitiveDataRedactor {

    private static final String REDACTED = "[REDACTED]";
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?i)(authorization|cookie|set-cookie|token|secret|password|api[_-]?key|client[_-]?secret)"
                    + "(\\s*[:=]\\s*)([^,\\s;]+)");
    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/-]+=*");
    private static final Pattern JSON_FIELD = Pattern.compile(
            "(?i)(\\\"(?:authorization|cookie|token|secret|password|api[_-]?key|client[_-]?secret)\\\"\\s*:\\s*\\\")[^\\\"]*(\\\")");

    private SensitiveDataRedactor() {
    }

    public static String redact(String value) {
        if (value == null || value.isBlank()) return value;
        String redacted = JSON_FIELD.matcher(value).replaceAll("$1" + REDACTED + "$2");
        redacted = BEARER.matcher(redacted).replaceAll("Bearer " + REDACTED);
        return ASSIGNMENT.matcher(redacted).replaceAll("$1$2" + REDACTED);
    }

    public static String safeExceptionMessage(Throwable failure) {
        if (failure == null) return null;
        String message = redact(failure.getMessage());
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
