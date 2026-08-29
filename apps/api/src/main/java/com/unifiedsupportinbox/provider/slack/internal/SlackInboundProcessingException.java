package com.unifiedsupportinbox.provider.slack.internal;

final class SlackInboundProcessingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    enum Kind {
        TRANSIENT,
        PERMANENT,
        MALFORMED
    }

    private final Kind kind;
    private final String errorCode;

    private SlackInboundProcessingException(Kind kind, String errorCode, String message) {
        super(message);
        this.kind = kind;
        this.errorCode = errorCode;
    }

    static SlackInboundProcessingException transientFailure(String errorCode, String message) {
        return new SlackInboundProcessingException(Kind.TRANSIENT, normalize(errorCode), message);
    }

    static SlackInboundProcessingException permanentFailure(String errorCode, String message) {
        return new SlackInboundProcessingException(Kind.PERMANENT, normalize(errorCode), message);
    }

    static SlackInboundProcessingException malformed(String errorCode, String message) {
        return new SlackInboundProcessingException(Kind.MALFORMED, normalize(errorCode), message);
    }

    Kind kind() {
        return kind;
    }

    String errorCode() {
        return errorCode;
    }

    private static String normalize(String code) {
        if (code == null || code.isBlank()) return "SLACK_PROCESSING_FAILED";
        String normalized = code.strip().toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9_]+", "_");
        if (normalized.isBlank()) normalized = "SLACK_PROCESSING_FAILED";
        return normalized.length() <= 128 ? normalized : normalized.substring(0, 128);
    }
}
