package com.unifiedsupportinbox.storage;

import java.util.UUID;

/** Generates internal object keys that never include an untrusted user filename. */
public final class AttachmentStorageKey {

    private AttachmentStorageKey() {
    }

    public static String generate() {
        UUID id = UUID.randomUUID();
        String value = id.toString();
        return "attachments/" + value.substring(0, 2) + "/" + value;
    }
}
