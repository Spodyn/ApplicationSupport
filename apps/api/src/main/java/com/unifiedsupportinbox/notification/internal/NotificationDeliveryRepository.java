package com.unifiedsupportinbox.notification.internal;

import com.unifiedsupportinbox.integration.IntegrationProvider;
import com.unifiedsupportinbox.notification.NotificationDeliveryStatus;
import com.unifiedsupportinbox.notification.NotificationDeliveryView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
class NotificationDeliveryRepository {

    private static final String COLUMNS = """
            id, deduplication_key, destination_id, rule_id, provider, integration_id,
            target_ref, event_type, severity, payload_json::text AS payload_json, status,
            wake_pending, attempts, replay_count, next_attempt_at, lease_until,
            last_error_category, last_error_code, terminal_reason, provider_message_ref,
            correlation_id, created_at, updated_at, sent_at
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    NotificationDeliveryRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    InsertResult insertIfAbsent(
            String deduplicationKey,
            UUID destinationId,
            UUID ruleId,
            IntegrationProvider provider,
            UUID integrationId,
            String targetRef,
            String eventType,
            String severity,
            String payloadJson,
            String correlationId) {
        List<DeliveryRecord> inserted = jdbc.query("""
                INSERT INTO notification_deliveries (
                    deduplication_key, destination_id, rule_id, provider, integration_id,
                    target_ref, event_type, severity, payload_json, status, wake_pending,
                    attempts, replay_count, next_attempt_at, lease_until, correlation_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), 'PENDING', TRUE,
                          0, 0, CURRENT_TIMESTAMP, NULL, ?)
                ON CONFLICT (deduplication_key) DO NOTHING
                RETURNING %s
                """.formatted(COLUMNS), prepared -> {
            prepared.setString(1, deduplicationKey);
            prepared.setObject(2, destinationId);
            prepared.setObject(3, ruleId);
            prepared.setString(4, provider.name());
            prepared.setObject(5, integrationId);
            prepared.setString(6, targetRef);
            prepared.setString(7, eventType);
            prepared.setString(8, severity);
            prepared.setString(9, payloadJson);
            prepared.setString(10, correlationId);
        }, this::mapDelivery);
        if (!inserted.isEmpty()) return new InsertResult(inserted.getFirst(), true);
        DeliveryRecord existing = findByDeduplicationKey(deduplicationKey)
                .orElseThrow(() -> new IllegalStateException("Deduplicated notification delivery disappeared."));
        return new InsertResult(existing, false);
    }

    Optional<DeliveryRecord> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM notification_deliveries WHERE id = ?",
                this::mapDelivery, id).stream().findFirst();
    }

    Optional<DeliveryRecord> findByDeduplicationKey(String key) {
        return jdbc.query("SELECT " + COLUMNS + " FROM notification_deliveries WHERE deduplication_key = ?",
                this::mapDelivery, key).stream().findFirst();
    }

    List<DeliveryRecord> listRecent(int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM notification_deliveries ORDER BY created_at DESC, id DESC LIMIT ?",
                this::mapDelivery, limit);
    }

    Optional<DeliveryRecord> claim(UUID id, Duration lease) {
        long leaseMillis = Math.max(1L, lease.toMillis());
        return jdbc.query("""
                UPDATE notification_deliveries
                SET status = 'PROCESSING',
                    wake_pending = FALSE,
                    attempts = attempts + 1,
                    lease_until = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                    terminal_reason = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                  AND (
                    (status = 'PENDING' AND wake_pending = TRUE AND next_attempt_at <= CURRENT_TIMESTAMP)
                    OR (status = 'PROCESSING' AND lease_until <= CURRENT_TIMESTAMP)
                  )
                RETURNING %s
                """.formatted(COLUMNS), prepared -> {
            prepared.setLong(1, leaseMillis);
            prepared.setObject(2, id);
        }, this::mapDelivery).stream().findFirst();
    }

    Optional<CurrentRouteRecord> findCurrentRoute(UUID destinationId, UUID ruleId) {
        return jdbc.query("""
                SELECT d.enabled AS destination_enabled,
                       r.enabled AS rule_enabled,
                       d.provider,
                       d.integration_id,
                       d.target_ref,
                       r.event_types::text AS event_types,
                       r.severity_filters::text AS severity_filters
                FROM notification_destinations d
                JOIN notification_rules r ON r.id = ? AND r.destination_id = d.id
                WHERE d.id = ?
                """, (rs, row) -> new CurrentRouteRecord(
                rs.getBoolean("destination_enabled"),
                rs.getBoolean("rule_enabled"),
                IntegrationProvider.valueOf(rs.getString("provider")),
                rs.getObject("integration_id", UUID.class),
                rs.getString("target_ref"),
                decodeList(rs.getString("event_types")),
                decodeList(rs.getString("severity_filters"))), ruleId, destinationId).stream().findFirst();
    }

    boolean markSent(UUID id, int attempt, String providerMessageRef) {
        return jdbc.update("""
                UPDATE notification_deliveries
                SET status = 'SENT', wake_pending = FALSE, lease_until = NULL,
                    next_attempt_at = CURRENT_TIMESTAMP, last_error_category = NULL,
                    last_error_code = NULL, terminal_reason = NULL,
                    provider_message_ref = ?, sent_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'PROCESSING' AND attempts = ?
                """, providerMessageRef, id, attempt) == 1;
    }

    boolean scheduleRetry(UUID id, int attempt, Duration delay, String errorCategory, String errorCode) {
        long delayMillis = Math.max(1L, delay.toMillis());
        return jdbc.update("""
                UPDATE notification_deliveries
                SET status = 'RETRY_SCHEDULED', wake_pending = FALSE, lease_until = NULL,
                    next_attempt_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'),
                    last_error_category = ?, last_error_code = ?, terminal_reason = NULL,
                    provider_message_ref = NULL, sent_at = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'PROCESSING' AND attempts = ?
                """, delayMillis, errorCategory, errorCode, id, attempt) == 1;
    }

    boolean markDlq(UUID id, int attempt, String reason, String errorCategory, String errorCode) {
        return jdbc.update("""
                UPDATE notification_deliveries
                SET status = 'DLQ', wake_pending = FALSE, lease_until = NULL,
                    next_attempt_at = CURRENT_TIMESTAMP, last_error_category = ?,
                    last_error_code = ?, terminal_reason = ?, provider_message_ref = NULL,
                    sent_at = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'PROCESSING' AND attempts = ?
                """, errorCategory, errorCode, reason, id, attempt) == 1;
    }

    boolean cancelClaim(UUID id, int attempt, String reason) {
        return jdbc.update("""
                UPDATE notification_deliveries
                SET status = 'CANCELLED', wake_pending = FALSE, lease_until = NULL,
                    next_attempt_at = CURRENT_TIMESTAMP, last_error_category = NULL,
                    last_error_code = NULL, terminal_reason = ?, provider_message_ref = NULL,
                    sent_at = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'PROCESSING' AND attempts = ?
                """, reason, id, attempt) == 1;
    }

    List<DueRecord> lockDueForRedispatch(int limit) {
        return jdbc.query("""
                SELECT %s
                FROM notification_deliveries
                WHERE (status = 'RETRY_SCHEDULED' AND next_attempt_at <= CURRENT_TIMESTAMP)
                   OR (status = 'PROCESSING' AND lease_until <= CURRENT_TIMESTAMP)
                ORDER BY CASE WHEN status = 'PROCESSING' THEN lease_until ELSE next_attempt_at END, id
                FOR UPDATE SKIP LOCKED
                LIMIT ?
                """.formatted(COLUMNS), (rs, row) -> new DueRecord(
                mapDelivery(rs, row), NotificationDeliveryStatus.valueOf(rs.getString("status"))), limit);
    }

    boolean markPendingForRedispatch(UUID id, NotificationDeliveryStatus expectedStatus, int attempt) {
        String extraPredicate = expectedStatus == NotificationDeliveryStatus.PROCESSING
                ? " AND attempts = ? AND lease_until <= CURRENT_TIMESTAMP"
                : " AND attempts = ? AND next_attempt_at <= CURRENT_TIMESTAMP";
        return jdbc.update("""
                UPDATE notification_deliveries
                SET status = 'PENDING', wake_pending = TRUE, lease_until = NULL,
                    next_attempt_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = ?
                """ + extraPredicate,
                id, expectedStatus.name(), attempt) == 1;
    }

    Optional<DeliveryRecord> replayDlq(UUID id) {
        return jdbc.query("""
                UPDATE notification_deliveries
                SET status = 'PENDING', wake_pending = TRUE, attempts = 0,
                    replay_count = replay_count + 1, next_attempt_at = CURRENT_TIMESTAMP,
                    lease_until = NULL, last_error_category = NULL, last_error_code = NULL,
                    terminal_reason = NULL, provider_message_ref = NULL, sent_at = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'DLQ'
                RETURNING %s
                """.formatted(COLUMNS), this::mapDelivery, id).stream().findFirst();
    }

    List<DeliveryRecord> cancelUnsentForDestination(UUID destinationId, String reason) {
        return cancelUnsent("destination_id", destinationId, reason);
    }

    List<DeliveryRecord> cancelUnsentForRule(UUID ruleId, String reason) {
        return cancelUnsent("rule_id", ruleId, reason);
    }

    private List<DeliveryRecord> cancelUnsent(String column, UUID id, String reason) {
        return jdbc.query("""
                UPDATE notification_deliveries
                SET status = 'CANCELLED', wake_pending = FALSE, lease_until = NULL,
                    next_attempt_at = CURRENT_TIMESTAMP, terminal_reason = ?,
                    last_error_category = NULL, last_error_code = NULL,
                    provider_message_ref = NULL, sent_at = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE %s = ? AND status IN ('PENDING', 'RETRY_SCHEDULED')
                RETURNING %s
                """.formatted(column, COLUMNS), prepared -> {
            prepared.setString(1, reason);
            prepared.setObject(2, id);
        }, this::mapDelivery);
    }

    void appendHistory(
            UUID deliveryId,
            NotificationDeliveryStatus status,
            int attempt,
            String reason,
            String errorCategory,
            String errorCode,
            String actorRef) {
        jdbc.update("""
                INSERT INTO notification_delivery_history (
                    delivery_id, status, attempt, reason, error_category, error_code, actor_ref
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, deliveryId, status.name(), attempt, reason, errorCategory, errorCode, actorRef);
    }

    private DeliveryRecord mapDelivery(ResultSet rs, int row) throws SQLException {
        return new DeliveryRecord(
                rs.getObject("id", UUID.class),
                rs.getString("deduplication_key"),
                rs.getObject("destination_id", UUID.class),
                rs.getObject("rule_id", UUID.class),
                IntegrationProvider.valueOf(rs.getString("provider")),
                rs.getObject("integration_id", UUID.class),
                rs.getString("target_ref"),
                rs.getString("event_type"),
                rs.getString("severity"),
                rs.getString("payload_json"),
                NotificationDeliveryStatus.valueOf(rs.getString("status")),
                rs.getBoolean("wake_pending"),
                rs.getInt("attempts"),
                rs.getInt("replay_count"),
                instant(rs, "next_attempt_at"),
                nullableInstant(rs, "lease_until"),
                rs.getString("last_error_category"),
                rs.getString("last_error_code"),
                rs.getString("terminal_reason"),
                rs.getString("provider_message_ref"),
                rs.getString("correlation_id"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                nullableInstant(rs, "sent_at"));
    }

    private List<String> decodeList(String raw) {
        try {
            JsonNode root = json.readTree(raw);
            List<String> values = new ArrayList<>();
            for (JsonNode value : root) values.add(value.stringValue());
            return List.copyOf(values);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored notification route filters are invalid.", exception);
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    record InsertResult(DeliveryRecord delivery, boolean created) {
    }

    record DueRecord(DeliveryRecord delivery, NotificationDeliveryStatus previousStatus) {
    }

    record CurrentRouteRecord(
            boolean destinationEnabled,
            boolean ruleEnabled,
            IntegrationProvider provider,
            UUID integrationId,
            String targetRef,
            List<String> eventTypes,
            List<String> severityFilters) {
    }
}

record DeliveryRecord(
        UUID id,
        String deduplicationKey,
        UUID destinationId,
        UUID ruleId,
        IntegrationProvider provider,
        UUID integrationId,
        String targetRef,
        String eventType,
        String severity,
        String payloadJson,
        NotificationDeliveryStatus status,
        boolean wakePending,
        int attempts,
        int replayCount,
        Instant nextAttemptAt,
        Instant leaseUntil,
        String lastErrorCategory,
        String lastErrorCode,
        String terminalReason,
        String providerMessageRef,
        String correlationId,
        Instant createdAt,
        Instant updatedAt,
        Instant sentAt) {

    NotificationDeliveryView toView() {
        return new NotificationDeliveryView(
                id, deduplicationKey, destinationId, ruleId, provider, integrationId, targetRef,
                eventType, severity, status, attempts, replayCount, nextAttemptAt,
                lastErrorCategory, lastErrorCode, terminalReason, providerMessageRef,
                correlationId, createdAt, updatedAt, sentAt);
    }
}
