package com.unifiedsupportinbox.provider.slack.internal;

import com.unifiedsupportinbox.messaging.MessageDeliveryProvider;
import com.unifiedsupportinbox.provider.ProviderAttachmentException;
import com.unifiedsupportinbox.provider.ProviderAttachmentGateway;
import com.unifiedsupportinbox.provider.ProviderAttachmentUploadMetadata;
import com.unifiedsupportinbox.storage.AttachmentMetadata;
import com.unifiedsupportinbox.storage.AttachmentMetadataCatalog;
import com.unifiedsupportinbox.storage.AttachmentObjectStorage;
import com.unifiedsupportinbox.storage.AttachmentSecurityService;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
class SlackOutboundAttachmentService {

    private final SlackProviderAttachmentGateway gateway;
    private final AttachmentMetadataCatalog metadata;
    private final ObjectProvider<AttachmentObjectStorage> objectStorage;
    private final ObjectProvider<AttachmentSecurityService> security;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    SlackOutboundAttachmentService(
            SlackProviderAttachmentGateway gateway,
            AttachmentMetadataCatalog metadata,
            ObjectProvider<AttachmentObjectStorage> objectStorage,
            ObjectProvider<AttachmentSecurityService> security,
            org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.gateway = gateway;
        this.metadata = metadata;
        this.objectStorage = objectStorage;
        this.security = security;
        this.jdbc = jdbc;
    }

    boolean uploadAll(MessageDeliveryProvider.DeliveryCommand command) {
        List<AttachmentMetadata> attachments = metadata.findByMessageId(command.messageId());
        if (attachments.isEmpty()) return false;
        if (command.externalThreadKey() == null || command.externalThreadKey().isBlank()) {
            throw ProviderAttachmentException.validation("SLACK_THREAD_MISSING");
        }

        AttachmentObjectStorage objects = objectStorage.getIfUnique();
        AttachmentSecurityService scanner = security.getIfUnique();
        if (objects == null || scanner == null) {
            throw ProviderAttachmentException.unavailable("ATTACHMENT_STORAGE_UNAVAILABLE", null);
        }

        for (AttachmentMetadata attachment : attachments) {
            if (attachment.providerFileId() != null) continue;
            AttachmentMetadata clean = scanner.requireClean(attachment.id());
            String contentType = clean.detectedContentType() != null
                    ? clean.detectedContentType()
                    : clean.contentType();
            ProviderAttachmentGateway.UploadedAttachment uploaded = gateway.upload(
                    new ProviderAttachmentGateway.UploadRequest(
                            command.integrationId(),
                            command.externalConversationId(),
                            command.externalThreadKey(),
                            new ProviderAttachmentUploadMetadata(
                                    clean.originalFilename(),
                                    contentType,
                                    clean.sizeBytes()),
                            () -> objects.open(clean.storageKey()),
                            command.idempotencyKey() + ":" + clean.id()),
                    preparedFile(clean.id()),
                    fileId -> recordPreparedFile(clean.id(), fileId));
            metadata.recordProviderFileId(clean.id(), uploaded.providerFileId());
        }
        return true;
    }

    private String preparedFile(java.util.UUID attachmentId) {
        return jdbc.query("SELECT slack_file_id FROM slack_outbound_uploads WHERE attachment_id = ?",
                (rs, row) -> rs.getString("slack_file_id"), attachmentId).stream().findFirst().orElse(null);
    }

    private String recordPreparedFile(java.util.UUID attachmentId, String fileId) {
        return jdbc.queryForObject("""
                INSERT INTO slack_outbound_uploads (attachment_id, slack_file_id) VALUES (?, ?)
                ON CONFLICT (attachment_id) DO UPDATE SET slack_file_id = slack_outbound_uploads.slack_file_id
                RETURNING slack_file_id
                """, String.class, attachmentId, fileId);
    }
}
