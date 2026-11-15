package com.unifiedsupportinbox.messaging.internal;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.CursorPosition;
import com.unifiedsupportinbox.IdempotencyResponse;
import com.unifiedsupportinbox.IdempotencyResult;
import com.unifiedsupportinbox.IdempotentCommandExecutor;
import com.unifiedsupportinbox.OutboxEventStore;
import com.unifiedsupportinbox.cases.CaseStatus;
import com.unifiedsupportinbox.identity.UserRole;
import com.unifiedsupportinbox.workflow.CaseTransitionPolicy;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
class CaseInternalNoteService {
    private final JdbcTemplate jdbc;
    private final IdempotentCommandExecutor idempotency;
    private final CaseTransitionPolicy policy;
    private final OutboxEventStore outbox;
    private final ObjectMapper json;

    CaseInternalNoteService(JdbcTemplate jdbc, IdempotentCommandExecutor idempotency,
            CaseTransitionPolicy policy, OutboxEventStore outbox, ObjectMapper json) {
        this.jdbc = jdbc;
        this.idempotency = idempotency;
        this.policy = policy;
        this.outbox = outbox;
        this.json = json;
    }

    IdempotencyResult add(UUID caseId, UUID actorId, String key, String body, String correlationId) {
        if (body == null || body.isBlank() || body.length() > 10000 || body.indexOf(0) >= 0) {
            throw ApiProblemException.validationFailed("Internal note text must contain 1 to 10000 characters.");
        }
        // Replays also require current Case read access.
        requireEligible(actorId);
        var result = idempotency.execute(actorId, "case.add-internal-note:" + caseId, key, Map.of("body", body),
                () -> save(caseId, actorId, body, correlationId));
        if (result.replayed()) requireCaseExists(caseId);
        return result;
    }

    private IdempotencyResponse save(UUID caseId, UUID actorId, String body, String correlationId) {
        var state = jdbc.query("""
                SELECT status, owner_user_id, waiting_until FROM cases WHERE id = ? FOR UPDATE
                """, (rs, row) -> {
                    var until = rs.getObject("waiting_until", OffsetDateTime.class);
                    return new CaseTransitionPolicy.State(CaseStatus.valueOf(rs.getString("status")),
                            rs.getObject("owner_user_id", UUID.class), until == null ? null : until.toInstant());
                }, caseId).stream().findFirst().orElseThrow(() -> ApiProblemException.notFound("Case was not found."));
        var author = requireEligible(actorId);
        boolean voted = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM case_ignore_votes WHERE case_id = ? AND user_id = ?)
                """, Boolean.class, caseId, actorId));
        var actor = new CaseTransitionPolicy.Actor(actorId, true, author.role(), Set.of(), voted);
        if (state.status() != CaseStatus.VERIFICATION) {
            throw ApiProblemException.conflict("Internal notes can only be added while the Case is in VERIFICATION.");
        }
        if (!policy.availableActions(state, actor).contains(CaseTransitionPolicy.Action.REPLY)) {
            throw ApiProblemException.accessDenied();
        }

        UUID noteId = jdbc.queryForObject("""
                INSERT INTO case_internal_notes (case_id, author_user_id, author_name, body)
                VALUES (?, ?, ?, ?) RETURNING id
                """, UUID.class, caseId, actorId, author.name(), body);
        var event = json.createObjectNode().put("noteId", noteId.toString()).put("caseId", caseId.toString());
        outbox.append("case.internal_note_added", "case_internal_note", noteId, event.toString(), correlationId);
        return new IdempotencyResponse(201, json.createObjectNode().put("noteId", noteId.toString()));
    }

    List<Note> history(UUID caseId, CursorPosition position, int limit, UUID viewer) {
        requireEligible(viewer);
        requireCaseExists(caseId);
        return jdbc.query("""
                SELECT id, body, author_name, created_at FROM case_internal_notes WHERE case_id = ?
                  AND (?::uuid IS NULL OR (created_at, id) < (?::timestamptz, ?::uuid))
                ORDER BY created_at DESC, id DESC LIMIT ?
                """, (rs, row) -> new Note(rs.getObject("id", UUID.class), rs.getString("body"),
                        rs.getString("author_name"), rs.getObject("created_at", OffsetDateTime.class).toInstant()),
                caseId, position == null ? null : position.id(),
                position == null ? null : OffsetDateTime.ofInstant(position.sortValue(), ZoneOffset.UTC),
                position == null ? null : position.id(), limit);
    }

    private void requireCaseExists(UUID caseId) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM cases WHERE id = ?)",
                Boolean.class, caseId))) {
            throw ApiProblemException.notFound("Case was not found.");
        }
    }

    private Author requireEligible(UUID userId) {
        return jdbc.query("""
                SELECT display_name, role FROM users WHERE id = ? AND active
                  AND role IN ('USER', 'ADMIN')
                  AND (valid_from IS NULL OR valid_from <= CURRENT_TIMESTAMP)
                  AND (valid_until IS NULL OR valid_until > CURRENT_TIMESTAMP)
                """, (rs, row) -> new Author(rs.getString("display_name"), UserRole.valueOf(rs.getString("role"))),
                userId).stream().findFirst().orElseThrow(ApiProblemException::accessDenied);
    }

    record Note(UUID id, String body, String authorName, Instant createdAt) {}
    private record Author(String name, UserRole role) {}
}
