package com.unifiedsupportinbox.storage.internal;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.storage.AttachmentMetadata;
import com.unifiedsupportinbox.storage.AttachmentMetadataCatalog;
import com.unifiedsupportinbox.storage.AttachmentObjectStorage;
import com.unifiedsupportinbox.storage.AttachmentScanStatus;
import com.unifiedsupportinbox.storage.AttachmentSecurityService;
import com.unifiedsupportinbox.storage.AttachmentStorageKey;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@ConditionalOnProperty(name = {
        "usi.object-storage.access-key",
        "usi.object-storage.secret-key"
})
class SecureAttachmentService {

    private final JdbcTemplate jdbc;
    private final AttachmentMetadataCatalog metadata;
    private final AttachmentObjectStorage objects;
    private final AttachmentFilePolicy policy;
    private final AttachmentSecurityService security;

    SecureAttachmentService(
            JdbcTemplate jdbc,
            AttachmentMetadataCatalog metadata,
            AttachmentObjectStorage objects,
            AttachmentFilePolicy policy,
            AttachmentSecurityService security) {
        this.jdbc = jdbc;
        this.metadata = metadata;
        this.objects = objects;
        this.policy = policy;
        this.security = security;
    }

    AttachmentMetadata upload(UUID caseId, UUID userId, MultipartFile file) {
        requireCurrentOwner(caseId, userId);
        if (file == null) {
            throw ApiProblemException.validationFailed("Attachment file is required.");
        }
        long size = file.getSize();
        if (size > AttachmentFilePolicy.MAX_FILE_BYTES) {
            throw ApiProblemException.validationFailed("Attachment exceeds the 25 MiB file limit.");
        }

        String filename = policy.normalizeFilename(file.getOriginalFilename());
        String declaredContentType = normalizedContentType(file.getContentType());
        String storageKey = AttachmentStorageKey.generate();
        boolean objectWritten = false;
        boolean metadataCreated = false;
        try (InputStream raw = file.getInputStream()) {
            byte[] prefix = raw.readNBytes(AttachmentFilePolicy.SNIFF_BYTES);
            String detectedContentType = policy.detectContentType(new ByteArrayInputStream(prefix));
            policy.validate(filename, declaredContentType, detectedContentType, size);

            MessageDigest digest = sha256();
            try (InputStream complete = new SequenceInputStream(new ByteArrayInputStream(prefix), raw);
                    DigestInputStream digesting = new DigestInputStream(complete, digest)) {
                objects.put(storageKey, digesting, size, declaredContentType);
                objectWritten = true;
            }

            AttachmentMetadata created = metadata.create(new AttachmentMetadataCatalog.CreateAttachment(
                    null,
                    caseId,
                    storageKey,
                    filename,
                    declaredContentType,
                    detectedContentType,
                    size,
                    HexFormat.of().formatHex(digest.digest()),
                    AttachmentScanStatus.PENDING,
                    null,
                    null));
            metadataCreated = true;
            return security.scan(created.id());
        } catch (AttachmentPolicyViolationException violation) {
            if (objectWritten && !metadataCreated) safeDelete(storageKey);
            throw ApiProblemException.validationFailed("Attachment was rejected: " + violation.code().name() + ".");
        } catch (IOException io) {
            if (objectWritten && !metadataCreated) safeDelete(storageKey);
            throw new IllegalStateException("Attachment upload could not be read.", io);
        } catch (RuntimeException failure) {
            if (objectWritten && !metadataCreated) safeDelete(storageKey);
            throw failure;
        }
    }

    Download openForDownload(UUID caseId, UUID attachmentId) {
        if (!metadata.belongsToCase(attachmentId, caseId)) {
            throw ApiProblemException.notFound("Attachment was not found for this Case.");
        }
        AttachmentMetadata clean = security.requireClean(attachmentId);
        return new Download(clean, objects.open(clean.storageKey()));
    }

    @Transactional
    void removePending(UUID caseId, UUID userId, UUID attachmentId) {
        requireCurrentOwner(caseId, userId);
        List<PendingObject> rows = jdbc.query("""
                SELECT storage_key
                FROM attachments
                WHERE id = ? AND case_id = ? AND message_id IS NULL
                FOR UPDATE
                """, (rs, rowNum) -> new PendingObject(rs.getString("storage_key")), attachmentId, caseId);
        if (rows.isEmpty()) {
            throw ApiProblemException.notFound("Pending attachment was not found for this Case.");
        }
        String storageKey = rows.getFirst().storageKey();
        objects.delete(storageKey);
        jdbc.update("DELETE FROM attachments WHERE id = ? AND case_id = ? AND message_id IS NULL", attachmentId, caseId);
    }

    private void requireCurrentOwner(UUID caseId, UUID userId) {
        if (caseId == null || userId == null) throw ApiProblemException.authenticationRequired();
        List<CaseOwner> rows = jdbc.query("SELECT owner_user_id, status FROM cases WHERE id = ?",
                (rs, rowNum) -> new CaseOwner(
                        rs.getObject("owner_user_id", UUID.class),
                        rs.getString("status")),
                caseId);
        if (rows.isEmpty()) throw ApiProblemException.notFound("Case was not found.");
        CaseOwner current = rows.getFirst();
        if (!"VERIFICATION".equals(current.status()) || !userId.equals(current.ownerUserId())) {
            throw ApiProblemException.accessDenied();
        }
    }

    private void safeDelete(String storageKey) {
        try {
            objects.delete(storageKey);
        } catch (RuntimeException ignored) {
            // A later object-storage lifecycle/operations sweep may remove an object left by a failed upload.
        }
    }

    private static String normalizedContentType(String value) {
        if (value == null || value.isBlank()) return "application/octet-stream";
        return value.split(";", 2)[0].strip().toLowerCase(java.util.Locale.ROOT);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }

    record Download(AttachmentMetadata metadata, InputStream content) {
    }

    private record CaseOwner(UUID ownerUserId, String status) {
    }

    private record PendingObject(String storageKey) {
    }
}
