package com.unifiedsupportinbox.messaging.internal;

import com.unifiedsupportinbox.ApiV1Conventions;
import com.unifiedsupportinbox.CursorCodec;
import com.unifiedsupportinbox.CursorPage;
import com.unifiedsupportinbox.CursorPosition;
import com.unifiedsupportinbox.messaging.MessageBodyFormat;
import com.unifiedsupportinbox.messaging.MessageDeliveryStatus;
import com.unifiedsupportinbox.messaging.MessageKind;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads locally persisted conversation history in newest-first cursor pages. */
@Service
class MessageHistoryService {

    private final MessageRepository messages;
    private final CursorCodec cursors;

    MessageHistoryService(MessageRepository messages, CursorCodec cursors) {
        this.messages = messages;
        this.cursors = cursors;
    }

    @Transactional(readOnly = true)
    CursorPage<MessageHistoryItem> history(UUID caseId, String before, Integer requestedLimit) {
        int limit = ApiV1Conventions.pageSize(requestedLimit);
        CursorPosition position = before == null ? null : cursors.decode(before, scope(caseId));
        List<MessageEntity> fetched = messages.findHistoryPage(
                caseId,
                position == null ? null : position.sortValue(),
                position == null ? null : position.id(),
                PageRequest.of(0, limit + 1));
        boolean hasMore = fetched.size() > limit;
        List<MessageEntity> page = hasMore ? fetched.subList(0, limit) : fetched;
        String nextCursor = hasMore
                ? cursors.encode(positionOf(page.getLast()), scope(caseId))
                : null;
        return new CursorPage<>(page.stream().map(MessageHistoryItem::from).toList(), nextCursor);
    }

    private static CursorPosition positionOf(MessageEntity message) {
        Instant timestamp = message.providerCreatedAt() == null ? message.createdAt() : message.providerCreatedAt();
        return new CursorPosition(timestamp, message.id());
    }

    private static String scope(UUID caseId) {
        return "message-history:" + caseId;
    }

    record MessageHistoryItem(
            UUID id,
            MessageKind kind,
            String body,
            MessageBodyFormat bodyFormat,
            boolean inbound,
            MessageDeliveryStatus deliveryStatus,
            Instant providerCreatedAt,
            Instant createdAt,
            Instant editedAt,
            Instant deletedAt,
            String authorName) {
        static MessageHistoryItem from(MessageEntity message) {
            return new MessageHistoryItem(
                    message.id(), message.kind(), message.body(), message.bodyFormat(), message.inbound(),
                    message.deliveryStatus(), message.providerCreatedAt(), message.createdAt(), message.editedAt(),
                    message.deletedAt(), message.authorName());
        }
    }
}
