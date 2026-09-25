package com.unifiedsupportinbox.audit;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.CursorCodec;
import com.unifiedsupportinbox.CursorPage;
import com.unifiedsupportinbox.CursorPosition;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Cursor-paged Case timeline backed directly by immutable audit events. */
@Service
class CaseActivityService {
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;

    private final CaseActivityRepository events;
    private final CursorCodec cursors;
    private final ObjectMapper json;

    CaseActivityService(CaseActivityRepository events, CursorCodec cursors, ObjectMapper json) {
        this.events = events;
        this.cursors = cursors;
        this.json = json;
    }

    @Transactional(readOnly = true)
    CursorPage<ActivityItem> activity(UUID caseId, String cursor, Integer requestedLimit) {
        if (!events.caseExists(caseId)) throw ApiProblemException.notFound("Case was not found.");
        int limit = requestedLimit == null ? DEFAULT_LIMIT : requestedLimit;
        if (limit < 1 || limit > MAX_LIMIT) throw new IllegalArgumentException("limit must be between 1 and 100");
        CursorPosition position = cursor == null ? null : cursors.decode(cursor, scope(caseId));
        List<RawActivity> fetched = events.find(caseId,
                position == null ? null : position.sortValue(), position == null ? null : position.id(), limit + 1);
        boolean hasMore = fetched.size() > limit;
        List<RawActivity> selected = hasMore ? fetched.subList(0, limit) : fetched;
        List<ActivityItem> items = selected.stream().map(this::view).toList();
        String next = hasMore ? cursors.encode(new CursorPosition(selected.getLast().occurredAt(), selected.getLast().id()), scope(caseId)) : null;
        return new CursorPage<>(items, next);
    }

    private ActivityItem view(RawActivity event) {
        return new ActivityItem(event.id(), event.actorType(), event.actorUserId(), actorLabel(event), event.action(),
                actionDescription(event.action()), event.occurredAt(), sanitizedMetadata(event.metadataJson()));
    }

    private JsonNode sanitizedMetadata(String value) {
        try {
            return AuditMetadata.sanitize(json.readTree(value));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Persisted audit metadata is invalid.", exception);
        }
    }

    private static String actorLabel(RawActivity event) {
        if (event.actorDisplayName() != null) return event.actorDisplayName();
        if (event.actorReference() != null) return event.actorReference();
        return switch (event.actorType()) {
            case SYSTEM -> "System";
            case PROVIDER -> "Provider";
            case USER -> "User";
            case ADMIN -> "Administrator";
        };
    }

    private static String actionDescription(String action) {
        return action.replace('_', ' ').toLowerCase(Locale.ROOT);
    }

    private static String scope(UUID caseId) { return "case-activity:" + caseId; }

    record RawActivity(UUID id, AuditActorType actorType, UUID actorUserId, String actorReference,
                       String actorDisplayName, String action, Instant occurredAt, String metadataJson) {}
    record ActivityItem(UUID id, AuditActorType actorType, UUID actorUserId, String actorLabel, String action,
                        String actionDescription, Instant occurredAt, JsonNode metadata) {}
}
