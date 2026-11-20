package com.unifiedsupportinbox.provider.internal;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.provider.HistoryRecoveryAdapter;
import com.unifiedsupportinbox.provider.HistoryRecoveryAdapter.Task;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Repository
class HistoryRecoveryStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    HistoryRecoveryStore(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    record Settings(boolean enabled, boolean automatic, int intervalMinutes, int lookbackMinutes,
            Instant lastRunAt, Instant nextRunAt) { }
    record ChannelProgress(UUID channelId, String channelName, String status, long scanned,
            long added, long pending, String errorCode) { }
    record Run(UUID id, String source, Instant from, Instant until, Instant createdAt,
            List<ChannelProgress> channels) { }
    record View(Settings settings, List<Run> runs) { }

    private void requireIntegration(UUID integration) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM integrations WHERE id=? AND provider IN ('SLACK','TEAMS'))",
                Boolean.class, integration))) throw ApiProblemException.notFound("Slack or Teams integration was not found.");
    }
    Settings settings(UUID integration) {
        requireIntegration(integration);
        return jdbc.query("SELECT * FROM integration_resync_settings WHERE integration_id=?", (rs, n) ->
                new Settings(rs.getBoolean("enabled"), rs.getBoolean("automatic"), rs.getInt("interval_minutes"),
                        rs.getInt("lookback_minutes"), instant(rs, "last_run_at"), instant(rs, "next_run_at")), integration)
                .stream().findFirst().orElse(new Settings(false, false, 30, 2880, null, null));
    }
    @Transactional
    public Settings save(UUID integration, Settings input) {
        requireIntegration(integration);
        if (input.intervalMinutes() < 1 || input.intervalMinutes() > 10080 || input.lookbackMinutes() < 1
                || input.lookbackMinutes() > 5256000 || input.automatic() && !input.enabled())
            throw ApiProblemException.validationFailed("Invalid recovery interval or history window.");
        jdbc.update("""
                INSERT INTO integration_resync_settings(integration_id,enabled,automatic,interval_minutes,lookback_minutes,next_run_at)
                VALUES (?,?,?,?,?,CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END)
                ON CONFLICT (integration_id) DO UPDATE SET enabled=EXCLUDED.enabled,automatic=EXCLUDED.automatic,
                  interval_minutes=EXCLUDED.interval_minutes,lookback_minutes=EXCLUDED.lookback_minutes,
                  next_run_at=CASE WHEN NOT EXCLUDED.automatic THEN NULL
                    WHEN NOT integration_resync_settings.automatic THEN CURRENT_TIMESTAMP
                    ELSE LEAST(integration_resync_settings.next_run_at,CURRENT_TIMESTAMP + EXCLUDED.interval_minutes * INTERVAL '1 minute') END
                """, integration, input.enabled(), input.automatic(), input.intervalMinutes(), input.lookbackMinutes(), input.automatic());
        return settings(integration);
    }
    @Transactional
    public UUID start(UUID integration, Instant from, String source, Instant now) {
        requireIntegration(integration);
        jdbc.query("SELECT integration_id FROM integration_resync_settings WHERE integration_id=? FOR UPDATE", rs -> { }, integration);
        jdbc.query("SELECT id FROM integrations WHERE id=? FOR UPDATE", rs -> { }, integration);
        if (!settings(integration).enabled()) throw ApiProblemException.validationFailed("Enable recovery before starting a run.");
        if (from != null && from.isAfter(now)) throw ApiProblemException.validationFailed("Recovery start must not be in the future.");
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM integrations WHERE id=? AND status='ENABLED')", Boolean.class, integration)))
            throw ApiProblemException.validationFailed("Integration must be enabled.");
        var active = jdbc.query("""
                SELECT r.id FROM integration_resync_runs r WHERE r.integration_id=? AND EXISTS(
                  SELECT 1 FROM integration_resync_tasks t WHERE t.run_id=r.id AND t.status IN ('QUEUED','RUNNING','WAITING'))
                ORDER BY r.created_at LIMIT 1
                """, (rs, n) -> rs.getObject(1, UUID.class), integration);
        if (!active.isEmpty()) {
            if (source.equals("automatic")) return null;
            throw ApiProblemException.validationFailed("A recovery scan is already active for this integration.");
        }
        UUID run = jdbc.queryForObject("INSERT INTO integration_resync_runs(integration_id,source,from_at,until_at) VALUES (?,?,?,?) RETURNING id",
                UUID.class, integration, source, at(from), at(now));
        jdbc.update("""
                INSERT INTO integration_resync_tasks(run_id,channel_id)
                SELECT ?,id FROM channels WHERE integration_id=? AND active AND NOT ignored
                """, run, integration);
        jdbc.update("UPDATE integration_resync_settings SET last_run_at=? WHERE integration_id=?", at(now), integration);
        return run;
    }
    @Transactional
    public void schedule(Instant now) {
        var due = jdbc.query("""
                SELECT s.integration_id FROM integration_resync_settings s JOIN integrations i ON i.id=s.integration_id
                WHERE s.enabled AND s.automatic AND s.next_run_at<=? AND i.status='ENABLED'
                ORDER BY s.next_run_at FOR UPDATE OF s SKIP LOCKED LIMIT 20
                """, (rs, n) -> rs.getObject(1, UUID.class), at(now));
        for (UUID integration : due) {
            Settings settings = settings(integration);
            if (start(integration, now.minus(Duration.ofMinutes(settings.lookbackMinutes())), "automatic", now) == null) continue;
            jdbc.update("UPDATE integration_resync_settings SET next_run_at=? WHERE integration_id=?",
                    at(now.plus(Duration.ofMinutes(settings.intervalMinutes()))), integration);
        }
    }
    @Transactional
    public Optional<Task> claim() {
        return jdbc.query("""
                WITH due AS (
                  SELECT t.id FROM integration_resync_tasks t JOIN integration_resync_runs r ON r.id=t.run_id
                  JOIN integration_resync_settings s ON s.integration_id=r.integration_id
                  JOIN integrations i ON i.id=r.integration_id
                  WHERE s.enabled AND i.status='ENABLED' AND s.provider_retry_at<=CURRENT_TIMESTAMP AND (
                    (t.status IN ('QUEUED','WAITING') AND t.next_attempt_at<=CURRENT_TIMESTAMP)
                    OR (t.status='RUNNING' AND t.lease_until<=CURRENT_TIMESTAMP))
                  ORDER BY t.next_attempt_at,t.id FOR UPDATE OF t SKIP LOCKED LIMIT 1
                ), claimed AS (
                  UPDATE integration_resync_tasks t SET status='RUNNING',lease_token=uuidv7(),
                    lease_until=CURRENT_TIMESTAMP+INTERVAL '2 minutes' FROM due WHERE t.id=due.id RETURNING t.*
                ) SELECT t.*,r.integration_id,r.source,r.from_at,r.until_at,c.external_channel_id,c.metadata_json::text,i.provider
                FROM claimed t JOIN integration_resync_runs r ON r.id=t.run_id JOIN channels c ON c.id=t.channel_id
                JOIN integrations i ON i.id=r.integration_id
                """, (rs, n) -> new Task(rs.getObject("id", UUID.class), rs.getObject("run_id", UUID.class),
                rs.getObject("integration_id", UUID.class), rs.getObject("channel_id", UUID.class), rs.getString("external_channel_id"),
                rs.getString("provider"), rs.getString("source"), instant(rs, "from_at"), instant(rs, "until_at"),
                rs.getString("thread_key"), rs.getString("cursor"), rs.getObject("lease_token", UUID.class),
                json.readTree(rs.getString("metadata_json")))).stream().findFirst();
    }
    @Transactional
    public void commit(Task task, HistoryRecoveryAdapter adapter, HistoryRecoveryAdapter.Page page) {
        // Fence expired workers before any writes, including inbound delivery and checkpoint.
        if (jdbc.query("SELECT id FROM integration_resync_tasks WHERE id=? AND lease_token=? AND status='RUNNING' FOR UPDATE",
                (rs, n) -> rs.getObject(1, UUID.class), task.id(), task.lease()).isEmpty()) return;
        boolean active = Boolean.TRUE.equals(jdbc.queryForObject("SELECT c.active AND NOT c.ignored AND s.enabled AND i.status='ENABLED' FROM channels c JOIN integrations i ON i.id=c.integration_id JOIN integration_resync_settings s ON s.integration_id=i.id WHERE c.id=?", Boolean.class, task.channelId()));
        if (!active) { fail(task, "CHANNEL_NO_LONGER_MONITORED", null); return; }
        for (var message : page.messages()) {
            UUID event = adapter.enqueue(task, message);
            if (event != null) jdbc.update("UPDATE inbound_events SET resync_task_id=? WHERE id=? AND resync_task_id IS NULL AND status='RECEIVED'",
                    task.id(), event);
        }
        for (String thread : page.threads()) jdbc.update("INSERT INTO integration_resync_tasks(run_id,channel_id,thread_key) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                task.runId(), task.channelId(), thread);
        jdbc.update("""
                UPDATE integration_resync_tasks SET status=?,cursor=?,scanned=scanned+?,error_code=NULL,
                  lease_token=NULL,lease_until=NULL,next_attempt_at=CURRENT_TIMESTAMP
                WHERE id=? AND lease_token=?
                """, page.nextCursor() == null ? "SUCCEEDED" : "QUEUED", page.nextCursor(), page.scanned(), task.id(), task.lease());
    }
    @Transactional
    public void fail(Task task, String code, Duration retry) {
        String safe = code != null && code.matches("[A-Z][A-Z0-9_]{0,127}") ? code : "RECOVERY_FAILED";
        int updated = jdbc.update("""
                UPDATE integration_resync_tasks SET status=?,error_code=?,lease_until=NULL,lease_token=NULL,
                  next_attempt_at=CURRENT_TIMESTAMP+? * INTERVAL '1 second' WHERE id=? AND lease_token=?
                """, retry == null ? "FAILED" : "WAITING", safe,
                retry == null ? 0 : Math.max(1, Math.min(86400, retry.toSeconds())), task.id(), task.lease());
        if (updated == 1 && retry != null && (safe.contains("RATE_LIMIT") || safe.endsWith("HTTP_429"))) {
            jdbc.update("""
                    UPDATE integration_resync_settings SET provider_retry_at=GREATEST(provider_retry_at,
                      CURRENT_TIMESTAMP+? * INTERVAL '1 second') WHERE integration_id=?
                    """, Math.max(1, Math.min(86400,retry.toSeconds())), task.integrationId());
        }
    }
    View view(UUID integration) {
        Settings settings = settings(integration);
        List<Run> runs = jdbc.query("SELECT * FROM integration_resync_runs WHERE integration_id=? ORDER BY created_at DESC,id DESC LIMIT 10",
                (rs, n) -> new Run(rs.getObject("id", UUID.class), rs.getString("source"), instant(rs, "from_at"),
                        instant(rs, "until_at"), instant(rs, "created_at"), List.of()), integration);
        return new View(settings, runs.stream().map(r -> new Run(r.id(),r.source(),r.from(),r.until(),r.createdAt(),progress(r.id()))).toList());
    }
    private List<ChannelProgress> progress(UUID run) {
        return jdbc.query("""
                SELECT c.id,c.name,sum(t.scanned) AS scanned,
                  CASE WHEN bool_or(t.status='FAILED') THEN 'INCOMPLETE'
                    WHEN bool_or(t.status='WAITING') THEN 'WAITING'
                    WHEN bool_and(t.status='SUCCEEDED') THEN 'SUCCEEDED' ELSE 'RUNNING' END AS status,
                  max(t.error_code) AS error_code,
                  (SELECT count(*) FROM messages m JOIN integration_resync_tasks mt ON mt.id=m.resync_task_id
                    WHERE mt.run_id=? AND mt.channel_id=c.id) AS added,
                  (SELECT count(*) FROM inbound_events e JOIN integration_resync_tasks et ON et.id=e.resync_task_id
                    WHERE et.run_id=? AND et.channel_id=c.id AND e.status<>'PROCESSED') AS pending,
                  (SELECT max(e.error_code) FROM inbound_events e JOIN integration_resync_tasks et ON et.id=e.resync_task_id
                    WHERE et.run_id=? AND et.channel_id=c.id AND e.status IN ('FAILED','DLQ')) AS inbound_error
                FROM integration_resync_tasks t JOIN channels c ON c.id=t.channel_id WHERE t.run_id=? GROUP BY c.id,c.name
                ORDER BY c.name,c.id
                """, (rs, n) -> new ChannelProgress(rs.getObject("id", UUID.class),rs.getString("name"),
                rs.getString("inbound_error") != null ? "INCOMPLETE" : rs.getLong("pending") > 0 && rs.getString("status").equals("SUCCEEDED") ? "PROCESSING" : rs.getString("status"),
                rs.getLong("scanned"),rs.getLong("added"),rs.getLong("pending"),
                rs.getString("error_code") == null ? rs.getString("inbound_error") : rs.getString("error_code")), run,run,run,run);
    }
    private static OffsetDateTime at(Instant value) { return value == null ? null : value.atOffset(ZoneOffset.UTC); }
    private static Instant instant(java.sql.ResultSet rs, String field) throws java.sql.SQLException {
        var value=rs.getObject(field,OffsetDateTime.class); return value==null ? null : value.toInstant();
    }
}
