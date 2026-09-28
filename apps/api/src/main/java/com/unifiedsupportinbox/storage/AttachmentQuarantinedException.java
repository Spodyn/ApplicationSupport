package com.unifiedsupportinbox.storage;

/** Raised when a caller attempts to use attachment bytes that have not passed security scanning. */
public final class AttachmentQuarantinedException extends RuntimeException {

    public AttachmentQuarantinedException() {
        super("Attachment is quarantined until a clean scan is available.");
    }
}
