package com.unifiedsupportinbox;

/** Fail-closed cursor validation error with a safe client-facing reason. */
public final class InvalidCursorException extends RuntimeException {

    public enum Reason {
        MALFORMED("The pagination cursor is malformed."),
        EXPIRED("The pagination cursor has expired."),
        UNSUPPORTED_VERSION("The pagination cursor version is unsupported."),
        SCOPE_MISMATCH("The pagination cursor does not match the current filters or sort."),
        INVALID_SIGNATURE("The pagination cursor is invalid.");

        private final String publicDetail;

        Reason(String publicDetail) {
            this.publicDetail = publicDetail;
        }

        public String publicDetail() {
            return publicDetail;
        }
    }

    private final Reason reason;

    public InvalidCursorException(Reason reason) {
        super(reason.publicDetail());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
