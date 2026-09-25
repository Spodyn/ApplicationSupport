package com.unifiedsupportinbox.audit;

import com.unifiedsupportinbox.CursorCodec;
import com.unifiedsupportinbox.CursorPage;
import com.unifiedsupportinbox.CursorPosition;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AuditSearchService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;

    private final AuditSearchRepository events;
    private final CursorCodec cursors;

    AuditSearchService(AuditSearchRepository events, CursorCodec cursors) {
        this.events = events;
        this.cursors = cursors;
    }

    @Transactional(readOnly = true)
    CursorPage<AuditEventItem> search(Filters filters, String before, Integer requestedLimit) {
        int limit = requestedLimit == null ? DEFAULT_LIMIT : requestedLimit;
        if (limit < 1 || limit > MAX_LIMIT) throw new IllegalArgumentException("limit must be between 1 and 100");
        CursorPosition position = before == null ? null : cursors.decode(before, scope(filters));
        List<AuditEventItem> fetched = events.find(filters,
                position == null ? null : position.sortValue(), position == null ? null : position.id(), limit + 1);
        boolean hasMore = fetched.size() > limit;
        List<AuditEventItem> page = hasMore ? fetched.subList(0, limit) : fetched;
        String next = hasMore ? cursors.encode(positionOf(page.getLast()), scope(filters)) : null;
        return new CursorPage<>(page, next);
    }

    private static CursorPosition positionOf(AuditEventItem event) {
        return new CursorPosition(event.occurredAt(), event.id());
    }

    private static String scope(Filters filters) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(filters.toString().getBytes(StandardCharsets.UTF_8));
            return "audit-search:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    record Filters(
            Instant from, Instant to, UUID actorId, String action, String entityType,
            UUID entityId, UUID caseId, String correlationId) {
    }

    record AuditEventItem(
            UUID id, AuditActorType actorType, UUID actorUserId, String action, String entityType,
            UUID entityId, UUID caseId, String correlationId, Instant occurredAt, String metadataJson) {
    }
}
