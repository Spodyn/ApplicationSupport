package com.unifiedsupportinbox.cases;

public final class CaseCreationRejectedException extends RuntimeException {

    private final Reason reason;

    public CaseCreationRejectedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        CHANNEL_NOT_FOUND,
        CHANNEL_INACTIVE,
        CHANNEL_IGNORED,
        CHANNEL_CUSTOMER_UNMAPPED,
        INTEGRATION_MISMATCH,
        PROVIDER_MISMATCH,
        INVALID_PROVIDER_CONTEXT
    }
}
