package com.unifiedsupportinbox.messaging.internal;

import com.unifiedsupportinbox.integration.IntegrationProvider;
import com.unifiedsupportinbox.messaging.MessageBodyFormat;
import com.unifiedsupportinbox.messaging.MessageDeliveryStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class MessageDeliveryRepository {

    private final JdbcTemplate jdbc;

    MessageDeliveryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<DeliveryMessageRecord> lockMessage(UUID messageId) {
        return jdbc.query("""
                SELECT m.id AS message_id,
                       m.case_id,
                       m.author_user_id,
                       c.owner_user_id AS case_owner_user_id,
                       c.status AS case_status,
                       c.provider,
                       c.integration_id,
                       i.provider AS integration_provider,
                       i.status AS integration_status,
                       c.external_conversation_id,
                       COALESCE(m.external_thread_key, c.external_thread_key) AS external_thread_key,
                       m.body,
                       m.body_format,
                       m.delivery_status,
                       m.correlation_id,
                       m.created_at,
                       COALESCE(attempts.attempt_count, 0) AS attempt_count,
                       attempts.first_attempt_at,
                       latest.id AS latest_attempt_id,
                       latest.attempt_no AS latest_attempt_no,
                       latest.started_at AS latest_started_at,
                       latest.finished_at AS latest_finished_at,
                       latest.error_category AS latest_error_category,
                       latest.error_code AS latest_error_code,
                       latest.next_retry_at AS latest_next_retry_at,
                       latest.provider_response_ref AS latest_provider_response_ref
                FROM messages m
                JOIN cases c ON c.id = m.case_id
                JOIN integrations i ON i.id = c.integration_id
                LEFT JOIN LATERAL (
                    SELECT COUNT(*)::integer AS attempt_count,
                           MIN(started_at) AS first_attempt_at
                    FROM delivery_attempts
                    WHERE message_id = m.id
                ) attempts ON TRUE
                LEFT JOIN LATERAL (
                    SELECT id, attempt_no, started_at, finished_at, error_category,
                           error_code, next_retry_at, provider_response_ref
                    FROM delivery_attempts
                    WHERE message_id = m.id
                    ORDER BY attempt_no DESC
                    LIMIT 1
                ) latest ON TRUE
                WHERE m.id = ?
                  AND m.kind = 'SUPPORT'
                  AND m.inbound = FALSE
                FOR UPDATE OF m
                """, this::mapMessage, messageId).stream().findFirst();
    }

    DeliveryAttemptRecord insertAttempt(UUID messageId, int attemptNo, Duration lease) {
        long leaseMillis = Math.max(1L, lease.toMillis());
        return jdbc.query("""
                INSERT INTO delivery_attempts (
                    message_id, attempt_no, started_at, finished_at,
                    error_category, error_code, next_retry_at, provider_response_ref
                ) VALUES (
                    ?, ?, CURRENT_TIMESTAMP, NULL, NULL, NULL,
                    CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'), NULL
                )
                RETURNING id, attempt_no, started_at, finished_at, error_category,
                          error_code, next_retry_at, provider_response_ref
                """, prepared -> {
            prepared.setObject(1, messageId);
            prepared.setInt(2, attemptNo);
            prepared.setLong(3, leaseMillis);
        }, this::mapAttempt).getFirst();
    }

    boolean markSending(UUID messageId) {
        return jdbc.update("""
                UPDATE messages
                SET delivery_status = 'SENDING'
                WHERE id = ? AND delivery_status = 'QUEUED'
                """, messageId) == 1;
    }

    boolean finishAttemptSuccess(
            UUID messageId,
            int attemptNo,
            String providerResponseRef) {
        return jdbc.update("""
                UPDATE delivery_attempts
                SET finished_at = CURRENT_TIMESTAMP,
                    error_category = NULL,
                    error_code = NULL,
                    next_retry_at = NULL,
                    provider_response_ref = ?
                WHERE message_id = ?
                  AND attempt_no = ?
                  AND finished_at IS NULL
                """, providerResponseRef, messageId, attemptNo) == 1;
    }

    boolean markSuccess(
            UUID messageId,
            MessageDeliveryStatus status,
            String providerMessageRef) {
        return jdbc.update("""
                UPDATE messages
                SET delivery_status = ?,
                    external_message_id = COALESCE(?, external_message_id)
                WHERE id = ? AND delivery_status = 'SENDING'
                """, status.name(), providerMessageRef, messageId) == 1;
    }

    boolean finishAttemptFailure(
            UUID messageId,
            int attemptNo,
            String errorCategory,
            String errorCode,
            Instant nextRetryAt) {
        return jdbc.update("""
                UPDATE delivery_attempts
                SET finished_at = CURRENT_TIMESTAMP,
                    error_category = ?,
                    error_code = ?,
                    next_retry_at = ?,
                    provider_response_ref = NULL
                WHERE message_id = ?
                  AND attempt_no = ?
                  AND finished_at IS NULL
                """, errorCategory, errorCode,
                nextRetryAt == null ? null : OffsetDateTime.ofInstant(nextRetryAt, java.time.ZoneOffset.UTC),
                messageId, attemptNo) == 1;
    }

    boolean markFailureState(UUID messageId, MessageDeliveryStatus status) {
        return jdbc.update("""
                UPDATE messages
                SET delivery_status = ?
                WHERE id = ? AND delivery_status = 'SENDING'
                """, status.name(), messageId) == 1;
    }

    boolean queueFailedForManualRetry(UUID messageId) {
        return jdbc.update("""
                UPDATE messages
                SET delivery_status = 'QUEUED'
                WHERE id = ? AND delivery_status = 'FAILED'
                """, messageId) == 1;
    }

    List<DueDeliveryRecord> lockDue(int limit) {
        return jdbc.query("""
                SELECT m.id AS message_id,
                       m.case_id,
                       m.correlation_id,
                       m.delivery_status,
                       latest.id AS attempt_id,
                       latest.attempt_no,
                       latest.started_at,
                       latest.finished_at,
                       latest.error_category,
                       latest.error_code,
                       latest.next_retry_at,
                       latest.provider_response_ref
                FROM messages m
                JOIN LATERAL (
                    SELECT id, attempt_no, started_at, finished_at, error_category,
                           error_code, next_retry_at, provider_response_ref
                    FROM delivery_attempts
                    WHERE message_id = m.id
                    ORDER BY attempt_no DESC
                    LIMIT 1
                ) latest ON TRUE
                WHERE m.kind = 'SUPPORT'
                  AND m.inbound = FALSE
                  AND (
                    (m.delivery_status = 'QUEUED'
                     AND latest.finished_at IS NOT NULL
                     AND latest.next_retry_at <= CURRENT_TIMESTAMP)
                    OR
                    (m.delivery_status = 'SENDING'
                     AND latest.finished_at IS NULL
                     AND latest.next_retry_at <= CURRENT_TIMESTAMP)
                  )
                ORDER BY latest.next_retry_at, m.id
                FOR UPDATE OF m SKIP LOCKED
                LIMIT ?
                """, (rs, row) -> new DueDeliveryRecord(
                rs.getObject("message_id", UUID.class),
                rs.getObject("case_id", UUID.class),
                rs.getString("correlation_id"),
                MessageDeliveryStatus.valueOf(rs.getString("delivery_status")),
                mapAttemptWithPrefix(rs)), limit);
    }

    boolean markRetryWakeDispatched(UUID messageId, int attemptNo) {
        return jdbc.update("""
                UPDATE delivery_attempts
                SET next_retry_at = NULL
                WHERE message_id = ?
                  AND attempt_no = ?
                  AND finished_at IS NOT NULL
                  AND next_retry_at <= CURRENT_TIMESTAMP
                """, messageId, attemptNo) == 1;
    }

    boolean recoverExpiredClaim(UUID messageId, int attemptNo) {
        int attempt = jdbc.update("""
                UPDATE delivery_attempts
                SET finished_at = CURRENT_TIMESTAMP,
                    error_category = 'TRANSIENT',
                    error_code = 'WORKER_LEASE_EXPIRED',
                    next_retry_at = NULL,
                    provider_response_ref = NULL
                WHERE message_id = ?
                  AND attempt_no = ?
                  AND finished_at IS NULL
                  AND next_retry_at <= CURRENT_TIMESTAMP
                """, messageId, attemptNo);
        if (attempt != 1) return false;
        return jdbc.update("""
                UPDATE messages
                SET delivery_status = 'QUEUED'
                WHERE id = ? AND delivery_status = 'SENDING'
                """, messageId) == 1;
    }

    private DeliveryMessageRecord mapMessage(ResultSet rs, int row) throws SQLException {
        Integer latestAttemptNo = (Integer) rs.getObject("latest_attempt_no");
        DeliveryAttemptRecord latest = latestAttemptNo == null
                ? null
                : new DeliveryAttemptRecord(
                        rs.getObject("latest_attempt_id", UUID.class),
                        latestAttemptNo,
                        instant(rs, "latest_started_at"),
                        nullableInstant(rs, "latest_finished_at"),
                        rs.getString("latest_error_category"),
                        rs.getString("latest_error_code"),
                        nullableInstant(rs, "latest_next_retry_at"),
                        rs.getString("latest_provider_response_ref"));
        return new DeliveryMessageRecord(
                rs.getObject("message_id", UUID.class),
                rs.getObject("case_id", UUID.class),
                rs.getObject("author_user_id", UUID.class),
                rs.getObject("case_owner_user_id", UUID.class),
                rs.getString("case_status"),
                IntegrationProvider.valueOf(rs.getString("provider")),
                rs.getObject("integration_id", UUID.class),
                IntegrationProvider.valueOf(rs.getString("integration_provider")),
                rs.getString("integration_status"),
                rs.getString("external_conversation_id"),
                rs.getString("external_thread_key"),
                rs.getString("body"),
                MessageBodyFormat.valueOf(rs.getString("body_format")),
                MessageDeliveryStatus.valueOf(rs.getString("delivery_status")),
                rs.getString("correlation_id"),
                instant(rs, "created_at"),
                rs.getInt("attempt_count"),
                nullableInstant(rs, "first_attempt_at"),
                latest);
    }

    private DeliveryAttemptRecord mapAttempt(ResultSet rs, int row) throws SQLException {
        return new DeliveryAttemptRecord(
                rs.getObject("id", UUID.class),
                rs.getInt("attempt_no"),
                instant(rs, "started_at"),
                nullableInstant(rs, "finished_at"),
                rs.getString("error_category"),
                rs.getString("error_code"),
                nullableInstant(rs, "next_retry_at"),
                rs.getString("provider_response_ref"));
    }

    private DeliveryAttemptRecord mapAttemptWithPrefix(ResultSet rs) throws SQLException {
        return new DeliveryAttemptRecord(
                rs.getObject("attempt_id", UUID.class),
                rs.getInt("attempt_no"),
                instant(rs, "started_at"),
                nullableInstant(rs, "finished_at"),
                rs.getString("error_category"),
                rs.getString("error_code"),
                nullableInstant(rs, "next_retry_at"),
                rs.getString("provider_response_ref"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    record DeliveryMessageRecord(
            UUID messageId,
            UUID caseId,
            UUID authorUserId,
            UUID caseOwnerUserId,
            String caseStatus,
            IntegrationProvider provider,
            UUID integrationId,
            IntegrationProvider integrationProvider,
            String integrationStatus,
            String externalConversationId,
            String externalThreadKey,
            String body,
            MessageBodyFormat bodyFormat,
            MessageDeliveryStatus deliveryStatus,
            String correlationId,
            Instant createdAt,
            int attemptCount,
            Instant firstAttemptAt,
            DeliveryAttemptRecord latestAttempt) {
    }

    record DeliveryAttemptRecord(
            UUID id,
            int attemptNo,
            Instant startedAt,
            Instant finishedAt,
            String errorCategory,
            String errorCode,
            Instant nextRetryAt,
            String providerResponseRef) {
    }

    record DueDeliveryRecord(
            UUID messageId,
            UUID caseId,
            String correlationId,
            MessageDeliveryStatus deliveryStatus,
            DeliveryAttemptRecord attempt) {
    }
}
