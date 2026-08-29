package com.unifiedsupportinbox.channel.internal;

import com.unifiedsupportinbox.channel.ChannelGroupingStrategy;
import com.unifiedsupportinbox.channel.DiscoveredChannel;
import com.unifiedsupportinbox.integration.IntegrationProvider;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
class ChannelRepository {

    private static final String SELECT_COLUMNS = """
            SELECT c.id,
                   c.integration_id,
                   i.provider,
                   c.external_channel_id,
                   c.name,
                   c.customer_id,
                   customer.name AS customer_name,
                   c.ignored,
                   c.grouping_strategy,
                   c.active,
                   c.last_message_at,
                   c.metadata_json::text AS metadata_json
            FROM channels c
            JOIN integrations i ON i.id = c.integration_id
            LEFT JOIN customers customer ON customer.id = c.customer_id
            """;

    private static final RowMapper<ChannelRecord> ROW_MAPPER = ChannelRepository::map;

    private final JdbcTemplate jdbc;

    ChannelRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<ChannelRecord> findAll() {
        return jdbc.query(
                SELECT_COLUMNS + " ORDER BY i.provider, c.name, c.id",
                ROW_MAPPER);
    }

    Optional<ChannelRecord> findById(UUID id) {
        Objects.requireNonNull(id, "id");
        return jdbc.query(SELECT_COLUMNS + " WHERE c.id = ?", ROW_MAPPER, id).stream().findFirst();
    }

    ChannelRecord upsertDiscovery(DiscoveredChannel discovered) {
        Objects.requireNonNull(discovered, "discovered");
        Objects.requireNonNull(discovered.integrationId(), "integrationId");
        Objects.requireNonNull(discovered.groupingStrategy(), "groupingStrategy");
        String externalChannelId = requiredText(discovered.externalChannelId(), "externalChannelId", 255);
        String name = requiredText(discovered.name(), "name", 255);
        String metadataJson = discovered.metadataJson() == null
                ? "{}"
                : requiredText(discovered.metadataJson(), "metadataJson", Integer.MAX_VALUE);

        return jdbc.query("""
                INSERT INTO channels (
                    integration_id,
                    external_channel_id,
                    name,
                    customer_id,
                    ignored,
                    grouping_strategy,
                    active,
                    last_message_at,
                    metadata_json
                )
                VALUES (?, ?, ?, NULL, FALSE, ?, ?, ?, CAST(? AS jsonb))
                ON CONFLICT (integration_id, external_channel_id)
                DO UPDATE SET
                    name = EXCLUDED.name,
                    active = EXCLUDED.active,
                    last_message_at = CASE
                        WHEN channels.last_message_at IS NULL THEN EXCLUDED.last_message_at
                        WHEN EXCLUDED.last_message_at IS NULL THEN channels.last_message_at
                        ELSE GREATEST(channels.last_message_at, EXCLUDED.last_message_at)
                    END,
                    metadata_json = EXCLUDED.metadata_json
                RETURNING id
                """, preparedStatement -> {
            preparedStatement.setObject(1, discovered.integrationId());
            preparedStatement.setString(2, externalChannelId);
            preparedStatement.setString(3, name);
            preparedStatement.setString(4, discovered.groupingStrategy().name());
            preparedStatement.setBoolean(5, discovered.active());
            preparedStatement.setObject(
                    6,
                    discovered.lastMessageAt() == null
                            ? null
                            : OffsetDateTime.ofInstant(discovered.lastMessageAt(), ZoneOffset.UTC));
            preparedStatement.setString(7, metadataJson);
        }, (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class)).stream()
                .findFirst()
                .flatMap(this::findById)
                .orElseThrow(() -> new IllegalStateException("Channel discovery upsert did not return a persisted channel."));
    }

    ChannelRecord setIgnored(UUID id, boolean ignored) {
        Objects.requireNonNull(id, "id");
        return jdbc.query("""
                UPDATE channels
                SET ignored = ?
                WHERE id = ?
                RETURNING id
                """, preparedStatement -> {
            preparedStatement.setBoolean(1, ignored);
            preparedStatement.setObject(2, id);
        }, (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class)).stream()
                .findFirst()
                .flatMap(this::findById)
                .orElseThrow(() -> new IllegalArgumentException("Channel was not found."));
    }

    private static ChannelRecord map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ChannelRecord(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("integration_id", UUID.class),
                IntegrationProvider.valueOf(resultSet.getString("provider")),
                resultSet.getString("external_channel_id"),
                resultSet.getString("name"),
                resultSet.getObject("customer_id", UUID.class),
                resultSet.getString("customer_name"),
                resultSet.getBoolean("ignored"),
                ChannelGroupingStrategy.valueOf(resultSet.getString("grouping_strategy")),
                resultSet.getBoolean("active"),
                nullableInstant(resultSet, "last_message_at"),
                resultSet.getString("metadata_json"));
    }

    private static Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static String requiredText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String normalized = value.strip();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds max length " + maxLength);
        }
        return normalized;
    }
}
