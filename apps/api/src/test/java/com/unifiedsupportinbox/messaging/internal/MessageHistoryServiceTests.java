package com.unifiedsupportinbox.messaging.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.unifiedsupportinbox.CursorCodec;
import com.unifiedsupportinbox.CursorPage;
import com.unifiedsupportinbox.messaging.MessageBodyFormat;
import com.unifiedsupportinbox.messaging.MessageKind;
import com.unifiedsupportinbox.storage.AttachmentMetadata;
import com.unifiedsupportinbox.storage.AttachmentMetadataCatalog;
import com.unifiedsupportinbox.storage.AttachmentScanStatus;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

class MessageHistoryServiceTests {

    private static final Instant NOW = Instant.parse("2026-09-24T22:00:00Z");

    @Test
    void returnsNewestFirstPageAndSignsTheLastReturnedPositionWithSafeAttachments() {
        MessageRepository repository = org.mockito.Mockito.mock(MessageRepository.class);
        AttachmentMetadataCatalog attachments = org.mockito.Mockito.mock(AttachmentMetadataCatalog.class);
        UUID caseId = UUID.randomUUID();
        MessageEntity newest = customerMessage(caseId, "2026-09-24T21:00:00Z");
        MessageEntity middle = customerMessage(caseId, "2026-09-24T20:00:00Z");
        MessageEntity oldest = customerMessage(caseId, "2026-09-24T19:00:00Z");
        when(repository.findHistoryPage(any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(newest, middle, oldest));
        AttachmentMetadata attachment = new AttachmentMetadata(
                UUID.randomUUID(),
                newest.id(),
                "attachments/aa/internal-only-key",
                "screen.png",
                "image/png",
                "image/png",
                842,
                "a".repeat(64),
                AttachmentScanStatus.CLEAN,
                null,
                "provider-file-secret-boundary",
                NOW);
        when(attachments.findByMessageIds(any())).thenReturn(Map.of(newest.id(), List.of(attachment)));
        CursorCodec codec = new CursorCodec(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8),
                Clock.fixed(NOW, ZoneOffset.UTC));
        MessageHistoryService service = new MessageHistoryService(repository, attachments, codec);

        CursorPage<MessageHistoryService.MessageHistoryItem> page = service.history(caseId, null, 2);

        assertThat(page.items()).extracting(MessageHistoryService.MessageHistoryItem::id)
                .containsExactly(newest.id(), middle.id());
        assertThat(page.items().getFirst().attachments()).containsExactly(
                new MessageHistoryService.MessageAttachmentItem(
                        attachment.id(), "screen.png", 842, "image/png", "image/png", AttachmentScanStatus.CLEAN));
        assertThat(page.items().getLast().attachments()).isEmpty();
        assertThat(page.nextCursor()).isNotBlank();
        assertThat(codec.decode(page.nextCursor(), "message-history:" + caseId).sortValue())
                .isEqualTo(middle.providerCreatedAt());
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        org.mockito.Mockito.verify(repository).findHistoryPage(any(), any(), any(), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(3);
    }

    private static MessageEntity customerMessage(UUID caseId, String timestamp) {
        Instant created = Instant.parse(timestamp);
        MessageEntity message = new MessageEntity(
                caseId, UUID.randomUUID().toString(), "thread", MessageKind.CUSTOMER,
                null, "customer", "Customer", "hello", MessageBodyFormat.PLAIN_TEXT,
                true, null, created, null, null, "correlation");
        set(message, "id", UUID.randomUUID());
        set(message, "createdAt", created);
        return message;
    }

    private static void set(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
