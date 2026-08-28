package com.unifiedsupportinbox;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class InboundEventStore {

    private static final Set<String> PROVIDERS = Set.of("SLACK", "TEAMS", "TELEGRAM");

    private static final String SELECT_COLUMNS = """
            SELECT id,
                   provider,
                   integration_id,
                   external_event_id,
                   payload_json::text AS payload_json,
                   status,
                   received_at,
                   processed_at,
                   error_code,
                   attempts,
                   correlation_id
            FROM inbound_events
            """;

    private static final RowMapper<InboundEvent> ROW_MAPPER = InboundEventStore::mapInboundEvent;

    private final JdbcTemplate jdbcTemplate;

    public InboundEventStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Persists an already-authenticated provider delivery before the caller acknowledges it.
     * Duplicate provider retries resolve to the existing durable row.
     */
    @Transactional
    public InboundEvent persistAuthenticated(
            String provider,
            UUID integrationId,
            String externalEventId,
            String payloadJson,
            String correlationId) {
        String normalizedProvider = normalizeProvider(provider);
        Objects.requireNonNull(integrationId, "integrationId");
        requireText(externalEventId, "externalEventId", 255);
        requireText(payloadJson, "payloadJson", Integer.MAX_VALUE);
        requireText(correlationId, "correlationId", 128);

        List<InboundEvent> inserted = jdbcTemplate.query("""
                INSERT INTO inbound_events (
                    provider, integration_id, external_event_id, payload_json,
                    status, received_at, processed_at, error_code, attempts, correlation_id
                )
                VALUES (?, ?, ?, CAST(? AS jsonb), 'RECEIVED', CURRENT_TIMESTAMP, NULL, NULL, 0, ?)
                ON CONFLICT (integration_id, external_event_id) DO NOTHING
                RETURNING id,
                          provider,
                          integration_id,
                          external_event_id,
                          payload_json::text AS payload_json,
                          status,
                          received_at,
                          processed_at,
                          error_code,
                          attempts,
                          correlation_id
                """, preparedStatement -> {
            preparedStatement.setString(1, normalizedProvider);
            preparedStatement.setObject(2, integrationId);
            preparedStatement.setString(3, externalEventId);
            preparedStatement.setString(4, payloadJson);
            preparedStatement.setString(5, correlationId);
        }, ROW_MAPPER);

        if (!inserted.isEmpty()) {
            return inserted.getFirst();
        }

        return findByIntegrationAndExternalEvent(integrationId, externalEventId)
                .orElseThrow(() -> new IllegalStateException(
                        "provider event dedup conflict resolved without a durable row"));
    }

    @Transactional(readOnly = true)
    public Optional<InboundEvent> findById(UUID id) {
        Objects.requireNonNull(id, "id");
        return jdbcTemplate.query(SELECT_COLUMNS + " WHERE id = ?", ROW_MAPPER, id).stream().findFirst();
    }

    Optional<InboundEvent> lockForProcessing(UUID id) {
        Objects.requireNonNull(id, "id");
        return jdbcTemplate.query(SELECT_COLUMNS + " WHERE id = ? FOR UPDATE", ROW_MAPPER, id)
                .stream()
                .findFirst();
    }

    int markProcessing(UUID id) {
        return jdbcTemplate.update("""
                UPDATE inbound_events
                SET status = 'PROCESSING',
                    processed_at = NULL,
                    error_code = NULL
                WHERE id = ?
                  AND status IN ('RECEIVED', 'FAILED')
                """, id);
    }

    int markProcessed(UUID id) {
        return jdbcTemplate.update("""
                UPDATE inbound_events
                SET status = 'PROCESSED',
                    processed_at = CURRENT_TIMESTAMP,
                    error_code = NULL,
                    attempts = attempts + 1
                WHERE id = ?
                  AND status = 'PROCESSING'
                """, id);
    }

    /**
     * Records a controlled processing failure after the business transaction has rolled back.
     * A hard process crash leaves the row RECEIVED and therefore naturally retryable on restart.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailedAfterRollback(UUID id, String errorCode) {
        Objects.requireNonNull(id, "id");
        requireText(errorCode, "errorCode", 128);
        jdbcTemplate.update("""
                UPDATE inbound_events
                SET status = 'FAILED',
                    processed_at = NULL,
                    error_code = ?,
                    attempts = attempts + 1
                WHERE id = ?
                  AND status IN ('RECEIVED', 'FAILED')
                """, errorCode, id);
    }

    private Optional<InboundEvent> findByIntegrationAndExternalEvent(UUID integrationId, String externalEventId) {
        return jdbcTemplate.query(
                        SELECT_COLUMNS + " WHERE integration_id = ? AND external_event_id = ?",
                        ROW_MAPPER,
                        integrationId,
                        externalEventId)
                .stream()
                .findFirst();
    }

    private static InboundEvent mapInboundEvent(ResultSet resultSet, int rowNumber) throws SQLException {
        return new InboundEvent(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("provider"),
                resultSet.getObject("integration_id", UUID.class),
                resultSet.getString("external_event_id"),
                resultSet.getString("payload_json"),
                resultSet.getString("status"),
                instant(resultSet, "received_at"),
                nullableInstant(resultSet, "processed_at"),
                resultSet.getString("error_code"),
                resultSet.getInt("attempts"),
                resultSet.getString("correlation_id"));
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static String normalizeProvider(String provider) {
        requireText(provider, "provider", 32);
        String normalized = provider.toUpperCase(Locale.ROOT);
        if (!PROVIDERS.contains(normalized)) {
            throw new IllegalArgumentException("unsupported v1 provider: " + provider);
        }
        return normalized;
    }

    static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds max length " + maxLength);
        }
        return value;
    }

    public record InboundEvent(
            UUID id,
            String provider,
            UUID integrationId,
            String externalEventId,
            String payloadJson,
            String status,
            Instant receivedAt,
            Instant processedAt,
            String errorCode,
            int attempts,
            String correlationId) {
    }
}
