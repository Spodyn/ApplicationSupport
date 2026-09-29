package com.unifiedsupportinbox.storage;

import java.time.Instant;
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

    /** Associates an outgoing upload only when it belongs to the supplied Case and is still unassociated. */
    AttachmentMetadata associateWithMessageForCase(UUID attachmentId, UUID messageId, UUID caseId);

    /** Returns true when attachment metadata is scoped to the supplied Case. */
    boolean belongsToCase(UUID attachmentId, UUID caseId);

    /** Deletes one still-unassociated attachment scoped to a Case and returns its metadata. */
    Optional<AttachmentMetadata> deleteUnassociated(UUID attachmentId, UUID caseId);

    /** Returns unassociated uploads older than the cutoff for object-store cleanup. */
    List<AttachmentMetadata> findUnassociatedCreatedBefore(Instant cutoff, int limit);

    /** Deletes an orphan after its object was removed. */
    boolean deleteUnassociated(UUID attachmentId);

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
            UUID caseId,
            String storageKey,
            String originalFilename,
            String contentType,
            String detectedContentType,
            long sizeBytes,
            String sha256,
            AttachmentScanStatus scanStatus,
            String scanError,
            String providerFileId) {

        /** Backward-compatible constructor for provider/message-associated attachments. */
        public CreateAttachment(
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
            this(messageId, null, storageKey, originalFilename, contentType, detectedContentType,
                    sizeBytes, sha256, scanStatus, scanError, providerFileId);
        }
    }
}
