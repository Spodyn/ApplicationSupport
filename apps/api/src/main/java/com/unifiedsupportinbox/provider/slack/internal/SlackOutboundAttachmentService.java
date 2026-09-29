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

    SlackOutboundAttachmentService(
            SlackProviderAttachmentGateway gateway,
            AttachmentMetadataCatalog metadata,
            ObjectProvider<AttachmentObjectStorage> objectStorage,
            ObjectProvider<AttachmentSecurityService> security) {
        this.gateway = gateway;
        this.metadata = metadata;
        this.objectStorage = objectStorage;
        this.security = security;
    }

    void uploadAll(MessageDeliveryProvider.DeliveryCommand command) {
        List<AttachmentMetadata> attachments = metadata.findByMessageId(command.messageId());
        if (attachments.isEmpty()) return;

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
                            command.idempotencyKey() + ":" + clean.id()));
            metadata.recordProviderFileId(clean.id(), uploaded.providerFileId());
        }
    }
}
