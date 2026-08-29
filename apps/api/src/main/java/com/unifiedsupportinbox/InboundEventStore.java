package com.unifiedsupportinbox;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
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
    private static final Set<String> FAILURE_CATEGORIES = Set.of(
            "TRANSIENT", "PERMANENT", "MALFORMED", "EXHAUSTED");

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
                   correlation_id,
                   failure_category,
                   next_attempt_at,
                   wake_pending,
                   dead_lettered_at
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
                          correlation_id,
                          failure_category,
                          next_attempt_at,
                          wake_pending,
                          dead_lettered_at
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

    /**
     * Reserves exactly one broker wake-up for a retryable inbound row. The caller must append the
     * corresponding outbox event in the same surrounding transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean reserveWake(UUID id) {
        Objects.requireNonNull(id, "id");
        return jdbcTemplate.update("""
                UPDATE inbound_events
                SET wake_pending = TRUE
                WHERE id = ?
                  AND status IN ('RECEIVED', 'FAILED')
                  AND wake_pending = FALSE
                  AND next_attempt_at <= CURRENT_TIMESTAMP
                """, id) == 1;
    }

    /**
     * Reserves due rows for broker redispatch with SKIP LOCKED so multiple scheduler instances can
     * safely run concurrently without creating duplicate outbox wake-ups.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<InboundEvent> reserveDueForDispatch(String provider, int limit) {
        String normalizedProvider = normalizeProvider(provider);
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("limit must be between 1 and 500");
        }
        return List.copyOf(jdbcTemplate.query("""
                WITH picked AS (
                    SELECT id
                    FROM inbound_events
                    WHERE provider = ?
                      AND status IN ('RECEIVED', 'FAILED')
                      AND wake_pending = FALSE
                      AND next_attempt_at <= CURRENT_TIMESTAMP
                    ORDER BY next_attempt_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                )
                UPDATE inbound_events AS event
                SET wake_pending = TRUE
                FROM picked
                WHERE event.id = picked.id
                RETURNING event.id,
                          event.provider,
                          event.integration_id,
                          event.external_event_id,
                          event.payload_json::text AS payload_json,
                          event.status,
                          event.received_at,
                          event.processed_at,
                          event.error_code,
                          event.attempts,
                          event.correlation_id,
                          event.failure_category,
                          event.next_attempt_at,
                          event.wake_pending,
                          event.dead_lettered_at
                """, preparedStatement -> {
            preparedStatement.setString(1, normalizedProvider);
            preparedStatement.setInt(2, limit);
        }, ROW_MAPPER));
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
                    error_code = NULL,
                    failure_category = NULL,
                    wake_pending = FALSE,
                    dead_lettered_at = NULL
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
                    failure_category = NULL,
                    attempts = attempts + 1,
                    next_attempt_at = CURRENT_TIMESTAMP,
                    wake_pending = FALSE,
                    dead_lettered_at = NULL
                WHERE id = ?
                  AND status = 'PROCESSING'
                """, id);
    }

    /**
     * Records a controlled processing failure after the business transaction has rolled back.
     * A hard process crash leaves the previous durable state intact and RabbitMQ redelivers the
     * unacknowledged wake-up on restart.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailedAfterRollback(UUID id, String errorCode) {
        markFailedAfterRollback(id, "TRANSIENT", errorCode);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailedAfterRollback(UUID id, String failureCategory, String errorCode) {
        Objects.requireNonNull(id, "id");
        String normalizedCategory = normalizeFailureCategory(failureCategory);
        requireText(errorCode, "errorCode", 128);
        jdbcTemplate.update("""
                UPDATE inbound_events
                SET status = 'FAILED',
                    processed_at = NULL,
                    error_code = ?,
                    failure_category = ?,
                    attempts = attempts + 1,
                    next_attempt_at = CURRENT_TIMESTAMP,
                    wake_pending = FALSE,
                    dead_lettered_at = NULL
                WHERE id = ?
                  AND status IN ('RECEIVED', 'FAILED')
                """, errorCode, normalizedCategory, id);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean scheduleRetry(UUID id, String errorCode, Duration delay) {
        Objects.requireNonNull(id, "id");
        requireText(errorCode, "errorCode", 128);
        Objects.requireNonNull(delay, "delay");
        long delayMillis = Math.max(1L, delay.toMillis());
        return jdbcTemplate.update("""
                UPDATE inbound_events
                SET status = 'FAILED',
                    processed_at = NULL,
                    error_code = ?,
                    failure_category = 'TRANSIENT',
                    next_attempt_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                    wake_pending = FALSE,
                    dead_lettered_at = NULL
                WHERE id = ?
                  AND status = 'FAILED'
                """, errorCode, delayMillis, id) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean moveToDlq(UUID id, String failureCategory, String errorCode) {
        Objects.requireNonNull(id, "id");
        String normalizedCategory = normalizeFailureCategory(failureCategory);
        requireText(errorCode, "errorCode", 128);
        return jdbcTemplate.update("""
                UPDATE inbound_events
                SET status = 'DLQ',
                    processed_at = NULL,
                    error_code = ?,
                    failure_category = ?,
                    next_attempt_at = CURRENT_TIMESTAMP,
                    wake_pending = FALSE,
                    dead_lettered_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND status = 'FAILED'
                """, errorCode, normalizedCategory, id) == 1;
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
                resultSet.getString("correlation_id"),
                resultSet.getString("failure_category"),
                instant(resultSet, "next_attempt_at"),
                resultSet.getBoolean("wake_pending"),
                nullableInstant(resultSet, "dead_lettered_at"));
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

    private static String normalizeFailureCategory(String failureCategory) {
        requireText(failureCategory, "failureCategory", 32);
        String normalized = failureCategory.toUpperCase(Locale.ROOT);
        if (!FAILURE_CATEGORIES.contains(normalized)) {
            throw new IllegalArgumentException("unsupported inbound failure category: " + failureCategory);
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
            String correlationId,
            String failureCategory,
            Instant nextAttemptAt,
            boolean wakePending,
            Instant deadLetteredAt) {
    }
}
