package com.unifiedsupportinbox.storage;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence boundary for attachment metadata; binary content remains in object storage. */
public interface AttachmentMetadataCatalog {

    AttachmentMetadata create(CreateAttachment command);

    Optional<AttachmentMetadata> findById(UUID attachmentId);

    List<AttachmentMetadata> findByMessageId(UUID messageId);

    AttachmentMetadata associateWithMessage(UUID attachmentId, UUID messageId);

    record CreateAttachment(
            UUID messageId,
            String storageKey,
            String originalFilename,
            String contentType,
            String detectedContentType,
            long sizeBytes,
            String sha256,
            AttachmentScanStatus scanStatus,
            String scanError,
            String providerFileId) {
    }
}
