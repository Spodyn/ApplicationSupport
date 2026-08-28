package com.unifiedsupportinbox;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class OutboxEventStore {

    private static final String SELECT_COLUMNS = """
            SELECT id,
                   type,
                   aggregate_type,
                   aggregate_id,
                   payload_json::text AS payload_json,
                   status,
                   next_attempt_at,
                   attempts,
                   created_at,
                   published_at,
                   correlation_id
            FROM outbox_events
            """;

    private static final RowMapper<OutboxEvent> ROW_MAPPER = OutboxEventStore::mapOutboxEvent;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public OutboxEventStore(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Appends an event to the durable outbox. A surrounding domain transaction is mandatory so
     * the aggregate change and this row can never commit independently.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent append(
            String type,
            String aggregateType,
            UUID aggregateId,
            String payloadJson,
            String correlationId) {
        InboundEventStore.requireText(type, "type", 128);
        InboundEventStore.requireText(aggregateType, "aggregateType", 128);
        Objects.requireNonNull(aggregateId, "aggregateId");
        InboundEventStore.requireText(payloadJson, "payloadJson", Integer.MAX_VALUE);
        InboundEventStore.requireText(correlationId, "correlationId", 128);

        return jdbcTemplate.query("""
                INSERT INTO outbox_events (
                    type, aggregate_type, aggregate_id, payload_json, status,
                    next_attempt_at, attempts, created_at, published_at, correlation_id
                )
                VALUES (?, ?, ?, CAST(? AS jsonb), 'PENDING', CURRENT_TIMESTAMP, 0,
                        CURRENT_TIMESTAMP, NULL, ?)
                RETURNING id,
                          type,
                          aggregate_type,
                          aggregate_id,
                          payload_json::text AS payload_json,
                          status,
                          next_attempt_at,
                          attempts,
                          created_at,
                          published_at,
                          correlation_id
                """, preparedStatement -> {
            preparedStatement.setString(1, type);
            preparedStatement.setString(2, aggregateType);
            preparedStatement.setObject(3, aggregateId);
            preparedStatement.setString(4, payloadJson);
            preparedStatement.setString(5, correlationId);
        }, ROW_MAPPER).getFirst();
    }

    /**
     * Claims due rows in a short DB-only transaction. PROCESSING rows whose lease expired are
     * reclaimable after a crash/restart. No broker/network call occurs in this transaction.
     */
    public List<OutboxEvent> claimDue(int limit, Duration lease) {
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("limit must be between 1 and 500");
        }
        Objects.requireNonNull(lease, "lease");
        long leaseMillis = Math.max(1L, lease.toMillis());

        List<OutboxEvent> claimed = transactionTemplate.execute(status -> jdbcTemplate.query("""
                WITH picked AS (
                    SELECT id
                    FROM outbox_events
                    WHERE status IN ('PENDING', 'PROCESSING')
                      AND next_attempt_at <= CURRENT_TIMESTAMP
                    ORDER BY next_attempt_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                )
                UPDATE outbox_events AS event
                SET status = 'PROCESSING',
                    attempts = event.attempts + 1,
                    next_attempt_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond')
                FROM picked
                WHERE event.id = picked.id
                RETURNING event.id,
                          event.type,
                          event.aggregate_type,
                          event.aggregate_id,
                          event.payload_json::text AS payload_json,
                          event.status,
                          event.next_attempt_at,
                          event.attempts,
                          event.created_at,
                          event.published_at,
                          event.correlation_id
                """, preparedStatement -> {
            preparedStatement.setInt(1, limit);
            preparedStatement.setLong(2, leaseMillis);
        }, ROW_MAPPER));
        return List.copyOf(Objects.requireNonNull(claimed, "transaction returned no claimed rows"));
    }

    public boolean markPublished(UUID id) {
        Objects.requireNonNull(id, "id");
        Integer updated = transactionTemplate.execute(status -> jdbcTemplate.update("""
                UPDATE outbox_events
                SET status = 'PUBLISHED',
                    published_at = CURRENT_TIMESTAMP,
                    next_attempt_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND status = 'PROCESSING'
                """, id));
        return Objects.requireNonNull(updated, "transaction returned no update count") == 1;
    }

    public boolean releaseForRetry(UUID id, Duration retryDelay) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(retryDelay, "retryDelay");
        long retryMillis = Math.max(1L, retryDelay.toMillis());
        Integer updated = transactionTemplate.execute(status -> jdbcTemplate.update("""
                UPDATE outbox_events
                SET status = 'PENDING',
                    published_at = NULL,
                    next_attempt_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond')
                WHERE id = ?
                  AND status = 'PROCESSING'
                """, retryMillis, id));
        return Objects.requireNonNull(updated, "transaction returned no update count") == 1;
    }

    @Transactional(readOnly = true)
    public Optional<OutboxEvent> findById(UUID id) {
        Objects.requireNonNull(id, "id");
        return jdbcTemplate.query(SELECT_COLUMNS + " WHERE id = ?", ROW_MAPPER, id).stream().findFirst();
    }

    private static OutboxEvent mapOutboxEvent(ResultSet resultSet, int rowNumber) throws SQLException {
        return new OutboxEvent(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("type"),
                resultSet.getString("aggregate_type"),
                resultSet.getObject("aggregate_id", UUID.class),
                resultSet.getString("payload_json"),
                resultSet.getString("status"),
                instant(resultSet, "next_attempt_at"),
                resultSet.getInt("attempts"),
                instant(resultSet, "created_at"),
                nullableInstant(resultSet, "published_at"),
                resultSet.getString("correlation_id"));
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    public record OutboxEvent(
            UUID id,
            String type,
            String aggregateType,
            UUID aggregateId,
            String payloadJson,
            String status,
            Instant nextAttemptAt,
            int attempts,
            Instant createdAt,
            Instant publishedAt,
            String correlationId) {
    }
}
