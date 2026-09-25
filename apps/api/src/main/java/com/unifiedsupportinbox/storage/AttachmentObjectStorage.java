package com.unifiedsupportinbox.storage;

import java.io.InputStream;

/** S3-compatible binary storage boundary for attachment bytes. */
public interface AttachmentObjectStorage {

    void put(String storageKey, InputStream content, long contentLength, String contentType);

    InputStream open(String storageKey);

    boolean exists(String storageKey);

    void delete(String storageKey);
}
