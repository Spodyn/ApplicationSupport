package com.unifiedsupportinbox.messaging.internal;

import com.unifiedsupportinbox.ApiV1Conventions;
import com.unifiedsupportinbox.CursorCodec;
import com.unifiedsupportinbox.CursorPage;
import com.unifiedsupportinbox.CursorPosition;
import com.unifiedsupportinbox.messaging.MessageBodyFormat;
import com.unifiedsupportinbox.messaging.MessageDeliveryStatus;
import com.unifiedsupportinbox.storage.AttachmentMetadata;
import com.unifiedsupportinbox.storage.AttachmentMetadataCatalog;
import com.unifiedsupportinbox.storage.AttachmentScanStatus;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads locally persisted conversation history in newest-first cursor pages. */
@Service
class MessageHistoryService {

    private final MessageRepository messages;
    private final AttachmentMetadataCatalog attachments;
    private final CursorCodec cursors;
    private final MessageDeliveryRepository deliveries;
    private final NamedParameterJdbcTemplate jdbc;
    private final CaseInternalNoteService notes;

    MessageHistoryService(
            MessageRepository messages,
            AttachmentMetadataCatalog attachments,
            CursorCodec cursors, MessageDeliveryRepository deliveries, NamedParameterJdbcTemplate jdbc,
            CaseInternalNoteService notes) {
        this.messages = messages;
        this.attachments = attachments;
        this.cursors = cursors;
        this.deliveries = deliveries;
        this.jdbc = jdbc;
        this.notes = notes;
    }

    @Transactional(readOnly = true)
    CursorPage<MessageHistoryItem> history(UUID caseId, String before, Integer requestedLimit, UUID viewer) {
        return history(caseId, before, requestedLimit, viewer, false);
    }

    @Transactional(readOnly = true)
    CursorPage<MessageHistoryItem> history(UUID caseId, String before, Integer requestedLimit, UUID viewer, boolean includeNotes) {
        int limit = ApiV1Conventions.pageSize(requestedLimit);
        String scope = (includeNotes ? "case-conversation:" : "message-history:") + caseId;
        CursorPosition position = before == null ? null : cursors.decode(before, scope);
        var noteItems = includeNotes ? notes.history(caseId, position, limit + 1, viewer) : List.<CaseInternalNoteService.Note>of();
        List<MessageEntity> fetched = position == null
                ? messages.findLatestHistoryPage(caseId, PageRequest.of(0, limit + 1))
                : messages.findBeforeHistoryPage(
                        caseId, position.sortValue(), position.id(), PageRequest.of(0, limit + 1));
        // Both sources are bounded by the same keyset and use PostgreSQL's UUID byte ordering.
        var merged = Stream.concat(
                        fetched.stream().map(message -> new HistoryRow(positionOf(message), message, null)),
                        noteItems.stream().map(note -> new HistoryRow(new CursorPosition(note.createdAt(), note.id()), null, note)))
                .sorted(Comparator.comparing((HistoryRow row) -> row.position().sortValue())
                        .thenComparing(row -> row.position().id().toString()).reversed())
                .limit(limit + 1L).toList();
        boolean hasMore = merged.size() > limit;
        var pageRows = hasMore ? merged.subList(0, limit) : merged;
        String nextCursor = hasMore ? cursors.encode(pageRows.getLast().position(), scope) : null;
        List<MessageEntity> page = pageRows.stream().map(HistoryRow::message).filter(java.util.Objects::nonNull).toList();
        Map<UUID, List<AttachmentMetadata>> attachmentsByMessage = attachments.findByMessageIds(
                page.stream().map(MessageEntity::id).toList());
        var failures = deliveries.failureDetails(page.stream()
                .filter(message -> message.deliveryStatus() == MessageDeliveryStatus.FAILED
                        || message.deliveryStatus() == MessageDeliveryStatus.QUEUED).map(MessageEntity::id).toList(), viewer);
        var authorIds = page.stream().filter(message -> message.authorName() == null)
                .map(MessageEntity::authorUserId).filter(java.util.Objects::nonNull).distinct().toList();
        Map<UUID, String> historicalAuthors = new HashMap<>();
        if (!authorIds.isEmpty()) {
            jdbc.query("SELECT id, display_name FROM users WHERE id IN (:ids)", Map.of("ids", authorIds), rs -> {
                historicalAuthors.put(rs.getObject("id", UUID.class), rs.getString("display_name"));
            });
        }
        return new CursorPage<>(pageRows.stream().map(row -> {
            if (row.note() != null) return MessageHistoryItem.from(row.note());
            var message = row.message();
            return MessageHistoryItem.from(message, attachmentsByMessage.getOrDefault(message.id(), List.of()),
                    failures.get(message.id()), historicalAuthors.get(message.authorUserId()));
        }).toList(), nextCursor);
    }

    private static CursorPosition positionOf(MessageEntity message) {
        Instant timestamp = message.providerCreatedAt() == null ? message.createdAt() : message.providerCreatedAt();
        return new CursorPosition(timestamp, message.id());
    }

    private record HistoryRow(CursorPosition position, MessageEntity message, CaseInternalNoteService.Note note) {}

    record MessageHistoryItem(
            UUID id,
            String kind,
            String body,
            MessageBodyFormat bodyFormat,
            boolean inbound,
            MessageDeliveryStatus deliveryStatus,
            boolean retryAllowed,
            String deliveryErrorCode,
            Instant nextRetryAt,
            Instant providerCreatedAt,
            Instant createdAt,
            Instant editedAt,
            Instant deletedAt,
            String authorName,
            List<MessageAttachmentItem> attachments) {
        static MessageHistoryItem from(CaseInternalNoteService.Note note) {
            return new MessageHistoryItem(note.id(), "INTERNAL_NOTE", note.body(), MessageBodyFormat.PLAIN_TEXT,
                    false, null, false, null, null, null, note.createdAt(), null, null, note.authorName(), List.of());
        }

        static MessageHistoryItem from(MessageEntity message, List<AttachmentMetadata> attachments, MessageDeliveryRepository.FailureView failure, String historicalAuthor) {
            String author = message.authorName();
            if (author == null) author = historicalAuthor == null ? message.authorExternalId() : historicalAuthor;
            return new MessageHistoryItem(
                    message.id(), message.kind().name(), message.body(), message.bodyFormat(), message.inbound(),
                    message.deliveryStatus(), failure != null && failure.retryAllowed(), failure == null ? null : failure.errorCode(),
                    failure == null ? null : failure.nextRetryAt(),
                    message.providerCreatedAt(), message.createdAt(), message.editedAt(),
                    message.deletedAt(), author,
                    attachments.stream().map(MessageAttachmentItem::from).toList());
        }
    }

    record MessageAttachmentItem(
            UUID id,
            String fileName,
            long sizeBytes,
            String contentType,
            String detectedContentType,
            AttachmentScanStatus scanStatus,
            String failureStage) {
        static MessageAttachmentItem from(AttachmentMetadata metadata) {
            return new MessageAttachmentItem(
                    metadata.id(),
                    metadata.originalFilename(),
                    metadata.sizeBytes(),
                    metadata.contentType(),
                    metadata.detectedContentType(),
                    metadata.scanStatus(),
                    metadata.scanStatus() != AttachmentScanStatus.ERROR ? null
                            : metadata.storageKey() == null ? "DOWNLOAD" : "SCAN");
        }
    }
}
