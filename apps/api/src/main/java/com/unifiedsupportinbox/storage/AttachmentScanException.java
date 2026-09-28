package com.unifiedsupportinbox.storage;

/** Stable, non-sensitive malware scanner failure suitable for persisted diagnostics. */
public final class AttachmentScanException extends Exception {

    public enum Code {
        SCANNER_UNAVAILABLE,
        SCANNER_TIMEOUT,
        SCANNER_IO_ERROR,
        SCANNER_PROTOCOL_ERROR
    }

    private final Code code;

    public AttachmentScanException(Code code) {
        this(code, null);
    }

    public AttachmentScanException(Code code, Throwable cause) {
        super(code.name(), cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
