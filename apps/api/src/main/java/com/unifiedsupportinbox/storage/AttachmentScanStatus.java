package com.unifiedsupportinbox.storage;

/** Frozen v1 malware/file-policy state for locally stored attachments. */
public enum AttachmentScanStatus {
    PENDING,
    CLEAN,
    INFECTED,
    ERROR
}
