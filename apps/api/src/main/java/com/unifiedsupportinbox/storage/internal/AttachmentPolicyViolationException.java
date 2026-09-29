package com.unifiedsupportinbox.storage.internal;

final class AttachmentPolicyViolationException extends Exception {

    enum Code {
        TOO_LARGE,
        BLOCKED_EXTENSION,
        BLOCKED_ARCHIVE,
        BLOCKED_EXECUTABLE,
        MIME_MISMATCH,
        UNSUPPORTED_TYPE
    }

    private final Code code;

    AttachmentPolicyViolationException(Code code) {
        super(code.name());
        this.code = code;
    }

    Code code() {
        return code;
    }
}
