package com.unifiedsupportinbox.ooo.internal;

import com.unifiedsupportinbox.ooo.OutOfOfficePolicyView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class OutOfOfficeRepository {

    private final JdbcTemplate jdbc;

    OutOfOfficeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    OutOfOfficePolicyView get() {
        return jdbc.query("SELECT enabled, message_template, send_once_per_closure, version, updated_at, updated_by FROM ooo_config WHERE id = 1", this::map)
                .stream().findFirst().orElseThrow(() -> new IllegalStateException("Out-of-office configuration is missing."));
    }

    Optional<OutOfOfficePolicyView> update(boolean enabled, String template, boolean sendOncePerClosure, long version, String updatedBy) {
        return jdbc.query("""
                UPDATE ooo_config SET enabled = ?, message_template = ?, send_once_per_closure = ?,
                    version = version + 1, updated_by = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = 1 AND version = ?
                RETURNING enabled, message_template, send_once_per_closure, version, updated_at, updated_by
                """, prepared -> {
            prepared.setBoolean(1, enabled);
            prepared.setString(2, template);
            prepared.setBoolean(3, sendOncePerClosure);
            prepared.setString(4, updatedBy);
            prepared.setLong(5, version);
        }, this::map).stream().findFirst();
    }

    private OutOfOfficePolicyView map(ResultSet rs, int row) throws SQLException {
        return new OutOfOfficePolicyView(rs.getBoolean("enabled"), rs.getString("message_template"),
                rs.getBoolean("send_once_per_closure"), rs.getLong("version"),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant(), rs.getString("updated_by"));
    }
}
