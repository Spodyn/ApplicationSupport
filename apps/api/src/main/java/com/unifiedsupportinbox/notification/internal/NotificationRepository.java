package com.unifiedsupportinbox.notification.internal;

import com.unifiedsupportinbox.integration.IntegrationProvider;
import com.unifiedsupportinbox.notification.NotificationDestinationView;
import com.unifiedsupportinbox.notification.NotificationRouteView;
import com.unifiedsupportinbox.notification.NotificationRuleView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
class NotificationRepository {

    private static final String DESTINATION_COLUMNS = """
            id, name, provider, integration_id, target_ref, enabled,
            secret_ref, config_ref, version, created_at, updated_at
            """;
    private static final String RULE_COLUMNS = """
            id, destination_id, name, enabled, event_types::text AS event_types,
            severity_filters::text AS severity_filters, version, created_at, updated_at
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    NotificationRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    List<DestinationRecord> findDestinations() {
        return jdbc.query("SELECT " + DESTINATION_COLUMNS + " FROM notification_destinations ORDER BY name, id",
                this::mapDestination);
    }

    Optional<DestinationRecord> findDestination(UUID id) {
        return jdbc.query("SELECT " + DESTINATION_COLUMNS + " FROM notification_destinations WHERE id = ?",
                this::mapDestination, id).stream().findFirst();
    }

    Optional<IntegrationProvider> findIntegrationProvider(UUID integrationId) {
        return jdbc.query("SELECT provider FROM integrations WHERE id = ?",
                (rs, row) -> IntegrationProvider.valueOf(rs.getString(1)), integrationId).stream().findFirst();
    }

    DestinationRecord createDestination(
            String name,
            IntegrationProvider provider,
            UUID integrationId,
            String targetRef,
            boolean enabled,
            String secretRef,
            String configRef) {
        return jdbc.query("""
                INSERT INTO notification_destinations (
                    name, provider, integration_id, target_ref, enabled, secret_ref, config_ref
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING %s
                """.formatted(DESTINATION_COLUMNS),
                prepared -> {
                    prepared.setString(1, name);
                    prepared.setString(2, provider.name());
                    prepared.setObject(3, integrationId);
                    prepared.setString(4, targetRef);
                    prepared.setBoolean(5, enabled);
                    prepared.setString(6, secretRef);
                    prepared.setString(7, configRef);
                }, this::mapDestination).getFirst();
    }

    Optional<DestinationRecord> updateDestination(
            UUID id,
            long expectedVersion,
            String name,
            IntegrationProvider provider,
            UUID integrationId,
            String targetRef,
            boolean enabled,
            String secretRef,
            String configRef) {
        return jdbc.query("""
                UPDATE notification_destinations
                SET name = ?, provider = ?, integration_id = ?, target_ref = ?, enabled = ?,
                    secret_ref = ?, config_ref = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND version = ?
                RETURNING %s
                """.formatted(DESTINATION_COLUMNS),
                prepared -> {
                    prepared.setString(1, name);
                    prepared.setString(2, provider.name());
                    prepared.setObject(3, integrationId);
                    prepared.setString(4, targetRef);
                    prepared.setBoolean(5, enabled);
                    prepared.setString(6, secretRef);
                    prepared.setString(7, configRef);
                    prepared.setObject(8, id);
                    prepared.setLong(9, expectedVersion);
                }, this::mapDestination).stream().findFirst();
    }

    boolean deleteDestination(UUID id, long expectedVersion) {
        return jdbc.update("DELETE FROM notification_destinations WHERE id = ? AND version = ?", id, expectedVersion) == 1;
    }

    List<RuleRecord> findRules() {
        return jdbc.query("SELECT " + RULE_COLUMNS + " FROM notification_rules ORDER BY destination_id, name, id",
                this::mapRule);
    }

    Optional<RuleRecord> findRule(UUID id) {
        return jdbc.query("SELECT " + RULE_COLUMNS + " FROM notification_rules WHERE id = ?",
                this::mapRule, id).stream().findFirst();
    }

    RuleRecord createRule(
            UUID destinationId,
            String name,
            boolean enabled,
            List<String> eventTypes,
            List<String> severityFilters) {
        return jdbc.query("""
                INSERT INTO notification_rules (
                    destination_id, name, enabled, event_types, severity_filters
                ) VALUES (?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb))
                RETURNING %s
                """.formatted(RULE_COLUMNS),
                prepared -> {
                    prepared.setObject(1, destinationId);
                    prepared.setString(2, name);
                    prepared.setBoolean(3, enabled);
                    prepared.setString(4, encodeList(eventTypes));
                    prepared.setString(5, encodeList(severityFilters));
                }, this::mapRule).getFirst();
    }

    Optional<RuleRecord> updateRule(
            UUID id,
            long expectedVersion,
            UUID destinationId,
            String name,
            boolean enabled,
            List<String> eventTypes,
            List<String> severityFilters) {
        return jdbc.query("""
                UPDATE notification_rules
                SET destination_id = ?, name = ?, enabled = ?, event_types = CAST(? AS jsonb),
                    severity_filters = CAST(? AS jsonb), version = version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND version = ?
                RETURNING %s
                """.formatted(RULE_COLUMNS),
                prepared -> {
                    prepared.setObject(1, destinationId);
                    prepared.setString(2, name);
                    prepared.setBoolean(3, enabled);
                    prepared.setString(4, encodeList(eventTypes));
                    prepared.setString(5, encodeList(severityFilters));
                    prepared.setObject(6, id);
                    prepared.setLong(7, expectedVersion);
                }, this::mapRule).stream().findFirst();
    }

    boolean deleteRule(UUID id, long expectedVersion) {
        return jdbc.update("DELETE FROM notification_rules WHERE id = ? AND version = ?", id, expectedVersion) == 1;
    }

    List<NotificationRouteView> findEnabledRoutes() {
        return jdbc.query("""
                SELECT d.id AS destination_id, r.id AS rule_id, d.provider, d.integration_id,
                       d.target_ref, r.event_types::text AS event_types,
                       r.severity_filters::text AS severity_filters,
                       d.version AS destination_version, r.version AS rule_version
                FROM notification_destinations d
                JOIN notification_rules r ON r.destination_id = d.id
                WHERE d.enabled = TRUE AND r.enabled = TRUE
                ORDER BY d.provider, d.id, r.id
                """, (rs, row) -> new NotificationRouteView(
                rs.getObject("destination_id", UUID.class),
                rs.getObject("rule_id", UUID.class),
                IntegrationProvider.valueOf(rs.getString("provider")),
                rs.getObject("integration_id", UUID.class),
                rs.getString("target_ref"),
                decodeList(rs.getString("event_types")),
                decodeList(rs.getString("severity_filters")),
                rs.getLong("destination_version"),
                rs.getLong("rule_version")));
    }

    void appendChange(
            String entityType,
            UUID entityId,
            String action,
            String actorRef,
            long version,
            Map<String, Object> redactedSnapshot) {
        jdbc.update("""
                INSERT INTO notification_configuration_changes (
                    entity_type, entity_id, action, actor_ref, entity_version, snapshot_json
                ) VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb))
                """, entityType, entityId, action, actorRef, version, encodeObject(redactedSnapshot));
    }

    private DestinationRecord mapDestination(ResultSet rs, int row) throws SQLException {
        return new DestinationRecord(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                IntegrationProvider.valueOf(rs.getString("provider")),
                rs.getObject("integration_id", UUID.class),
                rs.getString("target_ref"),
                rs.getBoolean("enabled"),
                rs.getString("secret_ref"),
                rs.getString("config_ref"),
                rs.getLong("version"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private RuleRecord mapRule(ResultSet rs, int row) throws SQLException {
        return new RuleRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("destination_id", UUID.class),
                rs.getString("name"),
                rs.getBoolean("enabled"),
                decodeList(rs.getString("event_types")),
                decodeList(rs.getString("severity_filters")),
                rs.getLong("version"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private List<String> decodeList(String raw) {
        try {
            JsonNode root = json.readTree(raw);
            List<String> result = new ArrayList<>();
            for (JsonNode value : root) {
                if (!value.isTextual()) throw new IllegalStateException("Notification filter JSON must contain strings.");
                result.add(value.stringValue());
            }
            return List.copyOf(result);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored notification filter JSON is invalid.", exception);
        }
    }

    private String encodeList(List<String> values) {
        try {
            return json.writeValueAsString(values);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Notification filters could not be serialized.", exception);
        }
    }

    private String encodeObject(Map<String, Object> value) {
        try {
            return json.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Notification audit snapshot could not be serialized.", exception);
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }
}

record DestinationRecord(
        UUID id,
        String name,
        IntegrationProvider provider,
        UUID integrationId,
        String targetRef,
        boolean enabled,
        String secretRef,
        String configRef,
        long version,
        Instant createdAt,
        Instant updatedAt) {
    NotificationDestinationView toView() {
        return new NotificationDestinationView(
                id, name, provider, integrationId, targetRef, enabled,
                secretRef != null, configRef != null, version, createdAt, updatedAt);
    }
}

record RuleRecord(
        UUID id,
        UUID destinationId,
        String name,
        boolean enabled,
        List<String> eventTypes,
        List<String> severityFilters,
        long version,
        Instant createdAt,
        Instant updatedAt) {
    NotificationRuleView toView() {
        return new NotificationRuleView(
                id, destinationId, name, enabled, eventTypes, severityFilters,
                version, createdAt, updatedAt);
    }
}
