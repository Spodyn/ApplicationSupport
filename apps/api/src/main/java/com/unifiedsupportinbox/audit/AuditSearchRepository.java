package com.unifiedsupportinbox.audit;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class AuditSearchRepository {

    private final JdbcTemplate jdbc;

    AuditSearchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<AuditSearchService.AuditEventItem> find(
            AuditSearchService.Filters filters,
            Instant beforeTime,
            UUID beforeId,
            int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, actor_type, actor_user_id, action, entity_type, entity_id, case_id,
                       correlation_id, occurred_at, metadata_json::text AS metadata_json
                FROM audit_events
                WHERE 1 = 1
                """);
        List<Object> parameters = new ArrayList<>();
        append(sql, parameters, "occurred_at >= ?", filters.from());
        append(sql, parameters, "occurred_at <= ?", filters.to());
        append(sql, parameters, "actor_user_id = ?", filters.actorId());
        append(sql, parameters, "action = ?", filters.action());
        append(sql, parameters, "entity_type = ?", filters.entityType());
        append(sql, parameters, "entity_id = ?", filters.entityId());
        append(sql, parameters, "case_id = ?", filters.caseId());
        append(sql, parameters, "correlation_id = ?", filters.correlationId());
        if (beforeTime != null) {
            sql.append(" AND (occurred_at, id) < (?, ?)");
            parameters.add(OffsetDateTime.ofInstant(beforeTime, ZoneOffset.UTC));
            parameters.add(beforeId);
        }
        sql.append(" ORDER BY occurred_at DESC, id DESC LIMIT ?");
        parameters.add(limit);
        return jdbc.query(sql.toString(), (rs, row) -> new AuditSearchService.AuditEventItem(
                rs.getObject("id", UUID.class),
                AuditActorType.valueOf(rs.getString("actor_type")),
                rs.getObject("actor_user_id", UUID.class),
                rs.getString("action"),
                rs.getString("entity_type"),
                rs.getObject("entity_id", UUID.class),
                rs.getObject("case_id", UUID.class),
                rs.getString("correlation_id"),
                rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                rs.getString("metadata_json")), parameters.toArray());
    }

    private static void append(StringBuilder sql, List<Object> parameters, String clause, Object value) {
        if (value != null) {
            sql.append(" AND ").append(clause);
            parameters.add(value instanceof Instant instant ? OffsetDateTime.ofInstant(instant, ZoneOffset.UTC) : value);
        }
    }
}
