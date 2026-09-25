package com.unifiedsupportinbox.storage;

public final class AttachmentObjectNotFoundException extends RuntimeException {

    public AttachmentObjectNotFoundException(String storageKey) {
        super("Attachment object does not exist for storage key: " + storageKey);
    }
}
