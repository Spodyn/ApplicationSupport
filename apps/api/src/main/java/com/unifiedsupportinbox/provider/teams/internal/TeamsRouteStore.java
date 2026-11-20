package com.unifiedsupportinbox.provider.teams.internal;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class TeamsRouteStore {
    private final JdbcTemplate jdbc;
    TeamsRouteStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    boolean approvedContext(UUID integration, String channel, String type) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM channels WHERE integration_id=? AND external_channel_id=?
                    AND metadata_json->>'teamsContextType' = ?)
                """, Boolean.class, integration, channel, type));
    }
    boolean isRecovery(UUID event) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT resync_task_id IS NOT NULL FROM inbound_events WHERE id=?", Boolean.class,event));
    }
    boolean withinMonitoring(UUID integration, java.time.Instant occurredAt) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM integrations WHERE id=? AND created_at<=?)",
                Boolean.class,integration,java.time.OffsetDateTime.ofInstant(occurredAt,java.time.ZoneOffset.UTC)));
    }
    void save(UUID integration, TeamsActivity a) {
        jdbc.update("""
                INSERT INTO teams_conversation_routes(integration_id,external_channel_id,thread_key,
                    connector_conversation_id,service_url,bot_id,context_type)
                VALUES (?,?,?,?,?,?,?) ON CONFLICT (integration_id,external_channel_id,thread_key)
                DO UPDATE SET connector_conversation_id=EXCLUDED.connector_conversation_id,
                    service_url=EXCLUDED.service_url,bot_id=EXCLUDED.bot_id,updated_at=CURRENT_TIMESTAMP
                """, integration, a.channelId(), a.thread() == null ? "" : a.thread(), a.conversationId(),
                a.serviceUrl(), a.botId(), a.contextType());
    }
    Route find(UUID integration, String channel, String thread) {
        return jdbc.query("SELECT * FROM teams_conversation_routes WHERE integration_id=? AND external_channel_id=? AND thread_key=?",
                (rs, row) -> new Route(rs.getString("connector_conversation_id"), rs.getString("service_url"),
                        rs.getString("bot_id"), rs.getString("context_type")), integration, channel, thread == null ? "" : thread)
                .stream().findFirst().orElse(null);
    }
    record Route(String conversationId, String serviceUrl, String botId, String contextType) { }
}
