package com.unifiedsupportinbox.storage;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Persistence boundary for attachment metadata; binary content remains in object storage. */
public interface AttachmentMetadataCatalog {

    AttachmentMetadata create(CreateAttachment command);

    Optional<AttachmentMetadata> findById(UUID attachmentId);

    List<AttachmentMetadata> findByMessageId(UUID messageId);

    Map<UUID, List<AttachmentMetadata>> findByMessageIds(Collection<UUID> messageIds);

    AttachmentMetadata associateWithMessage(UUID attachmentId, UUID messageId);

    /** Records the provider file identifier after a successful outbound upload. */
    AttachmentMetadata recordProviderFileId(UUID attachmentId, String providerFileId);

    /** Atomically claims a quarantined attachment for scanning. CLEAN/INFECTED/SCANNING rows are not claimable. */
    Optional<AttachmentMetadata> claimForScan(UUID attachmentId);

    /** Completes a previously claimed scan and persists normalized/detected metadata. */
    AttachmentMetadata completeScan(
            UUID attachmentId,
            String normalizedFilename,
            String detectedContentType,
            AttachmentScanStatus status,
            String scanError);

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
