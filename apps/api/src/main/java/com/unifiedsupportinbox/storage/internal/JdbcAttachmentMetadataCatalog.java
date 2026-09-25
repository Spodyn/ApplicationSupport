package com.unifiedsupportinbox.storage.internal;

import com.unifiedsupportinbox.storage.AttachmentMetadata;
import com.unifiedsupportinbox.storage.AttachmentMetadataCatalog;
import com.unifiedsupportinbox.storage.AttachmentScanStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JdbcAttachmentMetadataCatalog implements AttachmentMetadataCatalog {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final JdbcTemplate jdbc;

    JdbcAttachmentMetadataCatalog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public AttachmentMetadata create(CreateAttachment command) {
        requireCommand(command);
        UUID id = UUID.randomUUID();
        List<AttachmentMetadata> inserted = jdbc.query("""
                INSERT INTO attachments (
                    id, message_id, storage_key, original_filename, content_type,
                    detected_content_type, size_bytes, sha256, scan_status,
                    scan_error, provider_file_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id, message_id, storage_key, original_filename, content_type,
                          detected_content_type, size_bytes, sha256, scan_status,
                          scan_error, provider_file_id, created_at
                """,
                ps -> {
                    ps.setObject(1, id);
                    ps.setObject(2, command.messageId());
                    ps.setString(3, command.storageKey());
                    ps.setString(4, command.originalFilename());
                    ps.setString(5, command.contentType());
                    ps.setString(6, command.detectedContentType());
                    ps.setLong(7, command.sizeBytes());
                    ps.setString(8, command.sha256());
                    ps.setString(9, command.scanStatus().name());
                    ps.setString(10, command.scanError());
                    ps.setString(11, command.providerFileId());
                },
                JdbcAttachmentMetadataCatalog::map);
        if (inserted.size() != 1) {
            throw new IllegalStateException("Attachment metadata insert returned an unexpected number of rows.");
        }
        return inserted.getFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AttachmentMetadata> findById(UUID attachmentId) {
        if (attachmentId == null) return Optional.empty();
        return jdbc.query("""
                SELECT id, message_id, storage_key, original_filename, content_type,
                       detected_content_type, size_bytes, sha256, scan_status,
                       scan_error, provider_file_id, created_at
                FROM attachments
                WHERE id = ?
                """, JdbcAttachmentMetadataCatalog::map, attachmentId).stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AttachmentMetadata> findByMessageId(UUID messageId) {
        if (messageId == null) return List.of();
        return jdbc.query("""
                SELECT id, message_id, storage_key, original_filename, content_type,
                       detected_content_type, size_bytes, sha256, scan_status,
                       scan_error, provider_file_id, created_at
                FROM attachments
                WHERE message_id = ?
                ORDER BY created_at ASC, id ASC
                """, JdbcAttachmentMetadataCatalog::map, messageId);
    }

    @Override
    @Transactional
    public AttachmentMetadata associateWithMessage(UUID attachmentId, UUID messageId) {
        if (attachmentId == null) throw new IllegalArgumentException("attachmentId must not be null.");
        if (messageId == null) throw new IllegalArgumentException("messageId must not be null.");
        List<AttachmentMetadata> updated = jdbc.query("""
                UPDATE attachments
                SET message_id = ?
                WHERE id = ?
                RETURNING id, message_id, storage_key, original_filename, content_type,
                          detected_content_type, size_bytes, sha256, scan_status,
                          scan_error, provider_file_id, created_at
                """,
                ps -> {
                    ps.setObject(1, messageId);
                    ps.setObject(2, attachmentId);
                },
                JdbcAttachmentMetadataCatalog::map);
        if (updated.isEmpty()) {
            throw new IllegalArgumentException("Unknown attachmentId.");
        }
        if (updated.size() != 1) {
            throw new IllegalStateException("Attachment association updated an unexpected number of rows.");
        }
        return updated.getFirst();
    }

    private static AttachmentMetadata map(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime createdAt = rs.getObject("created_at", OffsetDateTime.class);
        return new AttachmentMetadata(
                rs.getObject("id", UUID.class),
                rs.getObject("message_id", UUID.class),
                rs.getString("storage_key"),
                rs.getString("original_filename"),
                rs.getString("content_type"),
                rs.getString("detected_content_type"),
                rs.getLong("size_bytes"),
                rs.getString("sha256"),
                AttachmentScanStatus.valueOf(rs.getString("scan_status").toUpperCase(Locale.ROOT)),
                rs.getString("scan_error"),
                rs.getString("provider_file_id"),
                createdAt.toInstant());
    }

    private static void requireCommand(CreateAttachment command) {
        if (command == null) throw new IllegalArgumentException("command must not be null.");
        requireText(command.storageKey(), "storageKey", 512);
        if (!command.storageKey().startsWith("attachments/")
                || command.storageKey().startsWith("/")
                || command.storageKey().contains("..")) {
            throw new IllegalArgumentException("storageKey must be an internal attachment object key.");
        }
        requireText(command.originalFilename(), "originalFilename", 512);
        optionalText(command.contentType(), "contentType", 255);
        optionalText(command.detectedContentType(), "detectedContentType", 255);
        if (command.sizeBytes() < 0) throw new IllegalArgumentException("sizeBytes must not be negative.");
        if (command.sha256() == null || !SHA256.matcher(command.sha256()).matches()) {
            throw new IllegalArgumentException("sha256 must be 64 lowercase hexadecimal characters.");
        }
        if (command.scanStatus() == null) throw new IllegalArgumentException("scanStatus must not be null.");
        if (command.scanStatus() == AttachmentScanStatus.ERROR) {
            requireText(command.scanError(), "scanError", 1024);
        } else if (command.scanError() != null) {
            throw new IllegalArgumentException("scanError is only valid for ERROR scan status.");
        }
        optionalText(command.providerFileId(), "providerFileId", 255);
    }

    private static void requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank.");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds max length " + maxLength + ".");
        }
    }

    private static void optionalText(String value, String field, int maxLength) {
        if (value != null && (value.isBlank() || value.length() > maxLength)) {
            throw new IllegalArgumentException(field + " must be null or non-blank and at most " + maxLength + " characters.");
        }
    }
}
