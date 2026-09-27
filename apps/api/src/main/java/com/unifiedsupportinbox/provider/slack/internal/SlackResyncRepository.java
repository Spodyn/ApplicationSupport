package com.unifiedsupportinbox.provider.slack.internal;

import com.unifiedsupportinbox.ApiProblemException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class SlackResyncRepository {

    private static final RowMapper<Job> ROW_MAPPER = SlackResyncRepository::map;
    private static final String COLUMNS = """
            SELECT id, integration_id, channel_id, external_channel_id, status,
                   oldest_ts, latest_ts, cursor, fetched_messages, scheduled_events,
                   max_messages, next_attempt_at, last_error_code, created_at,
                   updated_at, completed_at
            FROM slack_resync_jobs
            """;

    private final JdbcTemplate jdbc;

    SlackResyncRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    Job create(
            UUID integrationId,
            UUID channelId,
            String oldestTs,
            String latestTs,
            int maxMessages) {
        try {
            List<Job> created = jdbc.query("""
                    INSERT INTO slack_resync_jobs (
                        integration_id, channel_id, external_channel_id,
                        oldest_ts, latest_ts, max_messages
                    )
                    SELECT i.id, c.id, c.external_channel_id, ?, ?, ?
                    FROM integrations i
                    JOIN channels c ON c.integration_id = i.id
                    WHERE i.id = ?
                      AND i.provider = 'SLACK'
                      AND i.status = 'ENABLED'
                      AND c.id = ?
                      AND c.active = TRUE
                      AND c.ignored = FALSE
                    RETURNING id, integration_id, channel_id, external_channel_id, status,
                              oldest_ts, latest_ts, cursor, fetched_messages, scheduled_events,
                              max_messages, next_attempt_at, last_error_code, created_at,
                              updated_at, completed_at
                    """, prepared -> {
                prepared.setString(1, oldestTs);
                prepared.setString(2, latestTs);
                prepared.setInt(3, maxMessages);
                prepared.setObject(4, integrationId);
                prepared.setObject(5, channelId);
            }, ROW_MAPPER);
            if (created.isEmpty()) {
                throw ApiProblemException.conflict(
                        "Slack resync requires an enabled Slack integration and an active monitored channel.");
            }
            return created.getFirst();
        } catch (DataIntegrityViolationException conflict) {
            return findActive(integrationId, channelId).orElseThrow(() -> conflict);
        }
    }

    @Transactional(readOnly = true)
    Optional<Job> find(UUID integrationId, UUID jobId) {
        return jdbc.query(
                        COLUMNS + " WHERE integration_id = ? AND id = ?",
                        ROW_MAPPER,
                        integrationId,
                        jobId)
                .stream()
                .findFirst();
    }

    @Transactional
    Optional<Job> claimDue(Duration runningLease) {
        long leaseMillis = Math.max(1L, runningLease.toMillis());
        return jdbc.query("""
                WITH candidate AS (
                    SELECT id
                    FROM slack_resync_jobs
                    WHERE (
                        status IN ('QUEUED', 'WAITING')
                        AND next_attempt_at <= CURRENT_TIMESTAMP
                    ) OR (
                        status = 'RUNNING'
                        AND updated_at <= CURRENT_TIMESTAMP - (? * INTERVAL '1 millisecond')
                    )
                    ORDER BY next_attempt_at, created_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                UPDATE slack_resync_jobs job
                SET status = 'RUNNING',
                    updated_at = CURRENT_TIMESTAMP,
                    last_error_code = NULL
                FROM candidate
                WHERE job.id = candidate.id
                RETURNING job.id, job.integration_id, job.channel_id,
                          job.external_channel_id, job.status, job.oldest_ts,
                          job.latest_ts, job.cursor, job.fetched_messages,
                          job.scheduled_events, job.max_messages,
                          job.next_attempt_at, job.last_error_code, job.created_at,
                          job.updated_at, job.completed_at
                """, prepared -> prepared.setLong(1, leaseMillis), ROW_MAPPER)
                .stream()
                .findFirst();
    }

    @Transactional
    Job advance(
            UUID jobId,
            String nextCursor,
            int fetchedDelta,
            int scheduledDelta,
            boolean complete) {
        int safeFetched = Math.max(0, fetchedDelta);
        int safeScheduled = Math.max(0, scheduledDelta);
        List<Job> updated = jdbc.query("""
                UPDATE slack_resync_jobs
                SET cursor = ?,
                    fetched_messages = LEAST(max_messages, fetched_messages + ?),
                    scheduled_events = scheduled_events + ?,
                    status = CASE WHEN ? THEN 'SUCCEEDED' ELSE 'QUEUED' END,
                    next_attempt_at = CURRENT_TIMESTAMP,
                    last_error_code = NULL,
                    updated_at = CURRENT_TIMESTAMP,
                    completed_at = CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END
                WHERE id = ?
                  AND status = 'RUNNING'
                RETURNING id, integration_id, channel_id, external_channel_id, status,
                          oldest_ts, latest_ts, cursor, fetched_messages, scheduled_events,
                          max_messages, next_attempt_at, last_error_code, created_at,
                          updated_at, completed_at
                """, prepared -> {
            prepared.setString(1, blankToNull(nextCursor));
            prepared.setInt(2, safeFetched);
            prepared.setInt(3, safeScheduled);
            prepared.setBoolean(4, complete);
            prepared.setBoolean(5, complete);
            prepared.setObject(6, jobId);
        }, ROW_MAPPER);
        if (updated.isEmpty()) throw new IllegalStateException("Slack resync job is not running: " + jobId);
        return updated.getFirst();
    }

    @Transactional
    void defer(UUID jobId, String errorCode, Duration delay) {
        long delayMillis = Math.max(1L, delay.toMillis());
        int changed = jdbc.update("""
                UPDATE slack_resync_jobs
                SET status = 'WAITING',
                    next_attempt_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                    last_error_code = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND status = 'RUNNING'
                """, delayMillis, normalizedError(errorCode), jobId);
        if (changed != 1) throw new IllegalStateException("Slack resync job could not be deferred: " + jobId);
    }

    @Transactional
    void fail(UUID jobId, String errorCode) {
        int changed = jdbc.update("""
                UPDATE slack_resync_jobs
                SET status = 'FAILED',
                    last_error_code = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND status = 'RUNNING'
                """, normalizedError(errorCode), jobId);
        if (changed != 1) throw new IllegalStateException("Slack resync job could not fail: " + jobId);
    }

    @Transactional(readOnly = true)
    private Optional<Job> findActive(UUID integrationId, UUID channelId) {
        return jdbc.query(
                        COLUMNS + """
                                 WHERE integration_id = ?
                                   AND channel_id = ?
                                   AND status IN ('QUEUED', 'RUNNING', 'WAITING')
                                 ORDER BY created_at DESC, id DESC
                                 LIMIT 1
                                """,
                        ROW_MAPPER,
                        integrationId,
                        channelId)
                .stream()
                .findFirst();
    }

    private static Job map(ResultSet row, int rowNumber) throws SQLException {
        return new Job(
                row.getObject("id", UUID.class),
                row.getObject("integration_id", UUID.class),
                row.getObject("channel_id", UUID.class),
                row.getString("external_channel_id"),
                row.getString("status"),
                row.getString("oldest_ts"),
                row.getString("latest_ts"),
                row.getString("cursor"),
                row.getInt("fetched_messages"),
                row.getInt("scheduled_events"),
                row.getInt("max_messages"),
                instant(row, "next_attempt_at"),
                row.getString("last_error_code"),
                instant(row, "created_at"),
                instant(row, "updated_at"),
                nullableInstant(row, "completed_at"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        return row.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static Instant nullableInstant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String normalizedError(String value) {
        if (value == null || value.isBlank()) return "SLACK_RESYNC_FAILED";
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT)
                .replaceAll("[^A-Z0-9_]+", "_");
        if (normalized.isBlank()) return "SLACK_RESYNC_FAILED";
        return normalized.length() <= 128 ? normalized : normalized.substring(0, 128);
    }

    record Job(
            UUID id,
            UUID integrationId,
            UUID channelId,
            String externalChannelId,
            String status,
            String oldestTs,
            String latestTs,
            String cursor,
            int fetchedMessages,
            int scheduledEvents,
            int maxMessages,
            Instant nextAttemptAt,
            String lastErrorCode,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt) {

        int remaining() {
            return Math.max(0, maxMessages - fetchedMessages);
        }
    }
}
