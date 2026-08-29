package com.unifiedsupportinbox.integration.internal;

import com.unifiedsupportinbox.integration.IntegrationHealth;
import com.unifiedsupportinbox.integration.IntegrationProvider;
import com.unifiedsupportinbox.integration.IntegrationStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
class IntegrationRepository {

    private static final String SELECT_COLUMNS = """
            SELECT id,
                   provider,
                   display_name,
                   status,
                   health,
                   workspace_external_id,
                   workspace_name,
                   secret_ref,
                   config_json::text AS config_json,
                   last_event_at,
                   last_error_code,
                   created_at,
                   updated_at
            FROM integrations
            """;

    private static final RowMapper<IntegrationRecord> ROW_MAPPER = IntegrationRepository::map;

    private final JdbcTemplate jdbc;

    IntegrationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<IntegrationRecord> findAll() {
        return jdbc.query(
                SELECT_COLUMNS + " ORDER BY provider, display_name, id",
                ROW_MAPPER);
    }

    Optional<IntegrationRecord> findById(UUID id) {
        Objects.requireNonNull(id, "id");
        return jdbc.query(SELECT_COLUMNS + " WHERE id = ?", ROW_MAPPER, id)
                .stream()
                .findFirst();
    }

    IntegrationRecord create(
            IntegrationProvider provider,
            String displayName,
            IntegrationStatus status,
            IntegrationHealth health,
            String workspaceExternalId,
            String workspaceName,
            String secretRef,
            String configJson) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(health, "health");

        String normalizedDisplayName = requiredText(displayName, "displayName", 160);
        String normalizedWorkspaceExternalId = optionalText(workspaceExternalId, "workspaceExternalId", 255);
        String normalizedWorkspaceName = optionalText(workspaceName, "workspaceName", 255);
        String normalizedSecretRef = optionalText(secretRef, "secretRef", 512);
        String normalizedConfigJson = configJson == null ? "{}" : requiredText(configJson, "configJson", Integer.MAX_VALUE);

        return jdbc.query("""
                INSERT INTO integrations (
                    provider,
                    display_name,
                    status,
                    health,
                    workspace_external_id,
                    workspace_name,
                    secret_ref,
                    config_json
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                RETURNING id,
                          provider,
                          display_name,
                          status,
                          health,
                          workspace_external_id,
                          workspace_name,
                          secret_ref,
                          config_json::text AS config_json,
                          last_event_at,
                          last_error_code,
                          created_at,
                          updated_at
                """, preparedStatement -> {
            preparedStatement.setString(1, provider.name());
            preparedStatement.setString(2, normalizedDisplayName);
            preparedStatement.setString(3, status.name());
            preparedStatement.setString(4, health.name());
            preparedStatement.setString(5, normalizedWorkspaceExternalId);
            preparedStatement.setString(6, normalizedWorkspaceName);
            preparedStatement.setString(7, normalizedSecretRef);
            preparedStatement.setString(8, normalizedConfigJson);
        }, ROW_MAPPER).getFirst();
    }

    IntegrationRecord updateStatus(UUID id, IntegrationStatus status) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        return updateSingle("""
                UPDATE integrations
                SET status = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                RETURNING id,
                          provider,
                          display_name,
                          status,
                          health,
                          workspace_external_id,
                          workspace_name,
                          secret_ref,
                          config_json::text AS config_json,
                          last_event_at,
                          last_error_code,
                          created_at,
                          updated_at
                """, status.name(), id);
    }

    IntegrationRecord updateHealth(
            UUID id,
            IntegrationHealth health,
            Instant lastEventAt,
            String lastErrorCode) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(health, "health");
        String normalizedError = optionalText(lastErrorCode, "lastErrorCode", 128);

        return jdbc.query("""
                UPDATE integrations
                SET health = ?,
                    last_event_at = ?,
                    last_error_code = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                RETURNING id,
                          provider,
                          display_name,
                          status,
                          health,
                          workspace_external_id,
                          workspace_name,
                          secret_ref,
                          config_json::text AS config_json,
                          last_event_at,
                          last_error_code,
                          created_at,
                          updated_at
                """, preparedStatement -> {
            preparedStatement.setString(1, health.name());
            preparedStatement.setObject(2, lastEventAt == null ? null : OffsetDateTime.ofInstant(lastEventAt, java.time.ZoneOffset.UTC));
            preparedStatement.setString(3, normalizedError);
            preparedStatement.setObject(4, id);
        }, ROW_MAPPER).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Integration was not found."));
    }

    private IntegrationRecord updateSingle(String sql, String value, UUID id) {
        return jdbc.query(sql, ROW_MAPPER, value, id).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Integration was not found."));
    }

    private static IntegrationRecord map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new IntegrationRecord(
                resultSet.getObject("id", UUID.class),
                IntegrationProvider.valueOf(resultSet.getString("provider")),
                resultSet.getString("display_name"),
                IntegrationStatus.valueOf(resultSet.getString("status")),
                IntegrationHealth.valueOf(resultSet.getString("health")),
                resultSet.getString("workspace_external_id"),
                resultSet.getString("workspace_name"),
                resultSet.getString("secret_ref"),
                resultSet.getString("config_json"),
                nullableInstant(resultSet, "last_event_at"),
                resultSet.getString("last_error_code"),
                instant(resultSet, "created_at"),
                instant(resultSet, "updated_at"));
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static String requiredText(String value, String field, int maxLength) {
        String normalized = optionalText(value, field, maxLength);
        if (normalized == null) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }

    private static String optionalText(String value, String field, int maxLength) {
        if (value == null) return null;
        String normalized = value.strip();
        if (normalized.isEmpty()) return null;
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds max length " + maxLength);
        }
        return normalized;
    }
}
