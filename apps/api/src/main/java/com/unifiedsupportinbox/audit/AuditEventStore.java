package com.unifiedsupportinbox.audit;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Appends sanitized, transactionally-consistent administration audit events. */
@Repository
public class AuditEventStore {

    private static final String CORRELATION_ID_MDC_KEY = "correlationId";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AuditEventStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(
            AuditActorType actorType,
            UUID actorUserId,
            String action,
            String entityType,
            UUID entityId,
            UUID caseId,
            Map<String, ?> metadata) {
        Objects.requireNonNull(actorType, "actorType");
        requireText(action, "action", 128);
        requireText(entityType, "entityType", 64);
        Objects.requireNonNull(entityId, "entityId");

        jdbc.update("""
                INSERT INTO audit_events (
                    actor_type, actor_user_id, action, entity_type, entity_id, case_id,
                    correlation_id, metadata_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                """,
                actorType.name(),
                actorUserId,
                action,
                entityType,
                entityId,
                caseId,
                correlationId(),
                serializedMetadata(metadata));
    }

    private String serializedMetadata(Map<String, ?> metadata) {
        try {
            return objectMapper.writeValueAsString(AuditMetadata.sanitize(objectMapper.valueToTree(metadata)));
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("audit metadata cannot be serialized", exception);
        }
    }

    private static String correlationId() {
        String correlationId = MDC.get(CORRELATION_ID_MDC_KEY);
        return correlationId == null || correlationId.isBlank() ? UUID.randomUUID().toString() : correlationId;
    }

    private static void requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength || !value.equals(value.strip())) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }
}
