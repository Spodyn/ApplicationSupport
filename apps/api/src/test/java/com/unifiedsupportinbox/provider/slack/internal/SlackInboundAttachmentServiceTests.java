package com.unifiedsupportinbox.provider.slack.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.unifiedsupportinbox.messaging.InboundMessageCommandHandler;
import com.unifiedsupportinbox.provider.ProviderAttachmentGateway;
import com.unifiedsupportinbox.provider.ProviderAttachmentMetadata;
import com.unifiedsupportinbox.storage.AttachmentMetadata;
import com.unifiedsupportinbox.storage.AttachmentMetadataCatalog;
import com.unifiedsupportinbox.storage.AttachmentObjectStorage;
import com.unifiedsupportinbox.storage.AttachmentScanStatus;
import com.unifiedsupportinbox.storage.AttachmentSecurityService;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

class SlackInboundAttachmentServiceTests {

    @Test
    void associatesDownloadedSlackFileWithPersistedMessageAndScansIt() throws Exception {
        SlackProviderAttachmentGateway gateway = mock(SlackProviderAttachmentGateway.class);
        AttachmentMetadataCatalog metadata = mock(AttachmentMetadataCatalog.class);
        AttachmentObjectStorage objects = mock(AttachmentObjectStorage.class);
        AttachmentSecurityService security = mock(AttachmentSecurityService.class);
        SlackInboundAttachmentService service = new SlackInboundAttachmentService(
                gateway, metadata, provider(objects), provider(security));

        UUID integrationId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        byte[] content = new byte[] {1, 2, 3};
        when(metadata.findByMessageId(messageId)).thenReturn(List.of());
        when(gateway.download(any())).thenReturn(new ProviderAttachmentGateway.DownloadedAttachment(
                new ProviderAttachmentMetadata("F123", "invoice.pdf", "application/pdf", content.length),
                new ByteArrayInputStream(content)));
        AttachmentMetadata created = new AttachmentMetadata(
                UUID.randomUUID(),
                messageId,
                "attachments/aa/example",
                "invoice.pdf",
                "application/pdf",
                null,
                content.length,
                "0".repeat(64),
                AttachmentScanStatus.PENDING,
                null,
                "F123",
                Instant.now());
        when(metadata.create(any())).thenReturn(created);

        service.ingest(
                integrationId,
                new InboundMessageCommandHandler.Result(messageId, caseId, true),
                new ObjectMapper().readTree("""
                        {"files":[{"id":"F123"}]}
                        """));

        ArgumentCaptor<ProviderAttachmentGateway.DownloadRequest> download =
                ArgumentCaptor.forClass(ProviderAttachmentGateway.DownloadRequest.class);
        verify(gateway).download(download.capture());
        assertThat(download.getValue().integrationId()).isEqualTo(integrationId);
        assertThat(download.getValue().providerFileId()).isEqualTo("F123");

        ArgumentCaptor<AttachmentMetadataCatalog.CreateAttachment> create =
                ArgumentCaptor.forClass(AttachmentMetadataCatalog.CreateAttachment.class);
        verify(metadata).create(create.capture());
        assertThat(create.getValue().messageId()).isEqualTo(messageId);
        assertThat(create.getValue().providerFileId()).isEqualTo("F123");
        assertThat(create.getValue().originalFilename()).isEqualTo("invoice.pdf");
        assertThat(create.getValue().sizeBytes()).isEqualTo(3);
        assertThat(create.getValue().scanStatus()).isEqualTo(AttachmentScanStatus.PENDING);
        assertThat(create.getValue().sha256()).hasSize(64);

        verify(objects).put(anyString(), any(InputStream.class), anyLong(), anyString());
        verify(security).scan(created.id());
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfUnique()).thenReturn(value);
        return provider;
    }
}
