package com.unifiedsupportinbox.storage;

import java.time.Instant;
import java.util.UUID;

/** Browser-safe attachment metadata. Object-store credentials and provider URLs never belong here. */
public record AttachmentMetadata(
        UUID id,
        UUID messageId,
        String storageKey,
        String originalFilename,
        String contentType,
        String detectedContentType,
        long sizeBytes,
        String sha256,
        AttachmentScanStatus scanStatus,
        String scanError,
        String providerFileId,
        Instant createdAt) {
}
