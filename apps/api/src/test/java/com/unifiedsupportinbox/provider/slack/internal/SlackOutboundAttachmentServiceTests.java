package com.unifiedsupportinbox.provider.slack.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.unifiedsupportinbox.integration.IntegrationProvider;
import com.unifiedsupportinbox.messaging.MessageBodyFormat;
import com.unifiedsupportinbox.messaging.MessageDeliveryProvider;
import com.unifiedsupportinbox.provider.ProviderAttachmentGateway;
import com.unifiedsupportinbox.storage.AttachmentMetadata;
import com.unifiedsupportinbox.storage.AttachmentMetadataCatalog;
import com.unifiedsupportinbox.storage.AttachmentObjectStorage;
import com.unifiedsupportinbox.storage.AttachmentScanStatus;
import com.unifiedsupportinbox.storage.AttachmentSecurityService;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class SlackOutboundAttachmentServiceTests {

    @Test
    void uploadsCleanAttachmentAndRecordsProviderFileId() {
        SlackProviderAttachmentGateway gateway = mock(SlackProviderAttachmentGateway.class);
        AttachmentMetadataCatalog metadata = mock(AttachmentMetadataCatalog.class);
        AttachmentObjectStorage objects = mock(AttachmentObjectStorage.class);
        AttachmentSecurityService security = mock(AttachmentSecurityService.class);
        ObjectProvider<AttachmentObjectStorage> objectProvider = provider(objects);
        ObjectProvider<AttachmentSecurityService> securityProvider = provider(security);
        SlackOutboundAttachmentService service = new SlackOutboundAttachmentService(
                gateway, metadata, objectProvider, securityProvider);

        MessageDeliveryProvider.DeliveryCommand command = command();
        AttachmentMetadata attachment = attachment(command.messageId(), null);
        when(metadata.findByMessageId(command.messageId())).thenReturn(List.of(attachment));
        when(security.requireClean(attachment.id())).thenReturn(attachment);
        when(objects.open(attachment.storageKey())).thenReturn(new ByteArrayInputStream(new byte[] {1, 2, 3}));
        when(gateway.upload(any())).thenReturn(new ProviderAttachmentGateway.UploadedAttachment("F123", null));

        service.uploadAll(command);

        verify(gateway).upload(any());
        verify(metadata).recordProviderFileId(attachment.id(), "F123");
    }

    @Test
    void retrySkipsAttachmentThatAlreadyHasProviderFileId() {
        SlackProviderAttachmentGateway gateway = mock(SlackProviderAttachmentGateway.class);
        AttachmentMetadataCatalog metadata = mock(AttachmentMetadataCatalog.class);
        AttachmentObjectStorage objects = mock(AttachmentObjectStorage.class);
        AttachmentSecurityService security = mock(AttachmentSecurityService.class);
        SlackOutboundAttachmentService service = new SlackOutboundAttachmentService(
                gateway, metadata, provider(objects), provider(security));

        MessageDeliveryProvider.DeliveryCommand command = command();
        AttachmentMetadata attachment = attachment(command.messageId(), "F-already-sent");
        when(metadata.findByMessageId(command.messageId())).thenReturn(List.of(attachment));

        service.uploadAll(command);

        verify(gateway, never()).upload(any());
        verify(security, never()).requireClean(any());
    }

    private static MessageDeliveryProvider.DeliveryCommand command() {
        return new MessageDeliveryProvider.DeliveryCommand(
                UUID.randomUUID(),
                "stable-message-id",
                UUID.randomUUID(),
                IntegrationProvider.SLACK,
                UUID.randomUUID(),
                "C123",
                "1712000000.000001",
                "Support reply",
                MessageBodyFormat.PLAIN_TEXT,
                "corr-123");
    }

    private static AttachmentMetadata attachment(UUID messageId, String providerFileId) {
        return new AttachmentMetadata(
                UUID.randomUUID(),
                messageId,
                "attachments/aa/example",
                "invoice.pdf",
                "application/pdf",
                "application/pdf",
                3,
                "0".repeat(64),
                AttachmentScanStatus.CLEAN,
                null,
                providerFileId,
                Instant.now());
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfUnique()).thenReturn(value);
        return provider;
    }
}
