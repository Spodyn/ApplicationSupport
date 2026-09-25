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
        JsonNode metadata = sanitizedMetadata(event.metadataJson());
        return new ActivityItem(event.id(), event.actorType(), event.actorUserId(), actorLabel(event), event.action(),
                actionDescription(event.action()), workflowChange(metadata),
                event.relatedCaseId() == null ? null : new RelatedCase(event.relatedCaseId(), event.relatedCaseReference()),
                event.occurredAt(), metadata);
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
        return switch (action) {
            case "CASE_CLAIM" -> "Case claimed";
            case "CASE_IGNORE" -> "Case ignore vote recorded";
            case "CASE_REPLY" -> "Support reply sent";
            case "CASE_ASK_CUSTOMER" -> "Customer response requested";
            case "CASE_RESOLVE", "CASE_FORCE_RESOLVE" -> "Case resolved";
            case "CASE_ASSIGN", "CASE_REASSIGN" -> "Case assigned";
            case "CASE_UNASSIGN" -> "Case unassigned";
            case "CASE_SNOOZE" -> "Case snoozed";
            case "CASE_WAITING_TIMEOUT" -> "Waiting period expired";
            default -> action.replace('_', ' ').toLowerCase(Locale.ROOT);
        };
    }

    private static WorkflowChange workflowChange(JsonNode metadata) {
        JsonNode previous = metadata.path("previous").path("status");
        JsonNode current = metadata.path("current").path("status");
        if (previous.isTextual() || current.isTextual()) {
            return new WorkflowChange(previous.isTextual() ? previous.asString() : null,
                    current.isTextual() ? current.asString() : null);
        }
        JsonNode legacyCurrent = metadata.path("status");
        return legacyCurrent.isTextual() ? new WorkflowChange(metadata.path("previousStatus").asText(null), legacyCurrent.asString()) : null;
    }

    private static String scope(UUID caseId) { return "case-activity:" + caseId; }

    record RawActivity(UUID id, AuditActorType actorType, UUID actorUserId, String actorReference,
                       String actorDisplayName, String action, Instant occurredAt, String metadataJson,
                       UUID relatedCaseId, String relatedCaseReference) {}
    record ActivityItem(UUID id, AuditActorType actorType, UUID actorUserId, String actorLabel, String action,
                        String actionDescription, WorkflowChange workflowChange, RelatedCase relatedCase,
                        Instant occurredAt, JsonNode metadata) {}
    record WorkflowChange(String previousStatus, String currentStatus) {}
    record RelatedCase(UUID id, String reference) {}
}
