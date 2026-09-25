package com.unifiedsupportinbox.sla.internal;

import com.unifiedsupportinbox.sla.SlaPolicyView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class SlaPolicyRepository {
    private static final String COLUMNS = "id, name, extract(epoch from first_response_target) / 60 AS first_response_minutes, extract(epoch from unclaimed_warning) / 60 AS unclaimed_warning_minutes, extract(epoch from unclaimed_breach) / 60 AS unclaimed_breach_minutes, extract(epoch from in_progress_warning) / 60 AS in_progress_warning_minutes, extract(epoch from in_progress_breach) / 60 AS in_progress_breach_minutes, pause_waiting, version, updated_at";
    private final JdbcTemplate jdbc;
    SlaPolicyRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    SlaPolicyView active() { return jdbc.query("SELECT " + COLUMNS + " FROM sla_policies WHERE active", this::map).stream().findFirst().orElseThrow(() -> new IllegalStateException("Active SLA policy is missing.")); }
    Optional<SlaPolicyView> update(UUID id, long version, String name, long first, long uw, long ub, long iw, long ib, boolean pause) {
        return jdbc.query("""
                UPDATE sla_policies SET name = ?, first_response_target = ? * INTERVAL '1 minute', unclaimed_warning = ? * INTERVAL '1 minute', unclaimed_breach = ? * INTERVAL '1 minute', in_progress_warning = ? * INTERVAL '1 minute', in_progress_breach = ? * INTERVAL '1 minute', pause_waiting = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND active AND version = ? RETURNING %s
                """.formatted(COLUMNS), p -> { p.setString(1,name); p.setLong(2,first); p.setLong(3,uw); p.setLong(4,ub); p.setLong(5,iw); p.setLong(6,ib); p.setBoolean(7,pause); p.setObject(8,id); p.setLong(9,version); }, this::map).stream().findFirst();
    }
    private SlaPolicyView map(ResultSet r, int row) throws SQLException { return new SlaPolicyView(r.getObject("id", UUID.class), r.getString("name"), r.getLong("first_response_minutes"), r.getLong("unclaimed_warning_minutes"), r.getLong("unclaimed_breach_minutes"), r.getLong("in_progress_warning_minutes"), r.getLong("in_progress_breach_minutes"), r.getBoolean("pause_waiting"), r.getLong("version"), r.getObject("updated_at", OffsetDateTime.class).toInstant()); }
}
