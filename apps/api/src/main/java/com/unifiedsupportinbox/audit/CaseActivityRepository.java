package com.unifiedsupportinbox.audit;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class CaseActivityRepository {
    private final JdbcTemplate jdbc;

    CaseActivityRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    boolean caseExists(UUID caseId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM cases WHERE id = ?)", Boolean.class, caseId));
    }

    List<CaseActivityService.RawActivity> find(UUID caseId, UUID viewer, Instant beforeTime, UUID beforeId, int limit) {
        String cursorClause = beforeTime == null ? "" : " AND (event.occurred_at, event.id) < (?, ?)";
        Object[] parameters = beforeTime == null
                ? new Object[] {caseId, viewer, caseId, viewer, limit}
                : new Object[] {caseId, viewer, caseId, viewer, OffsetDateTime.ofInstant(beforeTime, ZoneOffset.UTC), beforeId, limit};
        return jdbc.query("""
                WITH visible_events AS (
                    SELECT id, actor_type, actor_user_id, actor_reference, action, occurred_at, metadata_json
                    FROM audit_events
                    WHERE case_id = ? AND (left(action, 11) <> 'CASE_SNOOZE' OR actor_user_id = ?)
                    UNION ALL
                    SELECT id,
                           CASE WHEN action IN ('CASE_SNOOZE', 'CASE_SNOOZE_CANCELLED') THEN 'USER' ELSE 'SYSTEM' END,
                           CASE WHEN action IN ('CASE_SNOOZE', 'CASE_SNOOZE_CANCELLED') THEN user_id ELSE NULL END,
                           NULL, action, occurred_at,
                           jsonb_build_object('snoozedUntil', until_at, 'snoozedAt', snoozed_at)
                    FROM case_snooze_history
                    WHERE case_id = ? AND user_id = ?
                )
                SELECT event.id, event.actor_type, event.actor_user_id, event.actor_reference, event.action,
                       event.occurred_at, event.metadata_json::text AS metadata_json,
                       user_account.display_name AS actor_display_name,
                       related_case.id AS related_case_id, related_case.reference AS related_case_reference
                FROM visible_events event
                LEFT JOIN users user_account ON user_account.id = event.actor_user_id
                LEFT JOIN cases related_case ON related_case.id = CASE
                    WHEN jsonb_exists(event.metadata_json, 'relatedCaseId')
                     AND event.metadata_json ->> 'relatedCaseId' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                    THEN (event.metadata_json ->> 'relatedCaseId')::uuid
                END
                WHERE TRUE
                """ + cursorClause + " ORDER BY event.occurred_at DESC, event.id DESC LIMIT ?",
                (rs, row) -> new CaseActivityService.RawActivity(
                        rs.getObject("id", UUID.class), AuditActorType.valueOf(rs.getString("actor_type")),
                        rs.getObject("actor_user_id", UUID.class), rs.getString("actor_reference"),
                        rs.getString("actor_display_name"), rs.getString("action"),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant(), rs.getString("metadata_json"),
                        rs.getObject("related_case_id", UUID.class), rs.getString("related_case_reference")),
                parameters);
    }
}
