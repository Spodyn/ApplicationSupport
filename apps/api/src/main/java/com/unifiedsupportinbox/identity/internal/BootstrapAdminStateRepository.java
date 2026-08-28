package com.unifiedsupportinbox.identity.internal;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class BootstrapAdminStateRepository {

    private final JdbcTemplate jdbcTemplate;

    BootstrapAdminStateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    State lockSingleton() {
        State state = jdbcTemplate.queryForObject(
                "SELECT consumed, admin_user_id FROM bootstrap_admin_state WHERE id = 1 FOR UPDATE",
                (resultSet, rowNumber) -> new State(
                        resultSet.getBoolean("consumed"),
                        resultSet.getObject("admin_user_id", UUID.class)));
        if (state == null) {
            throw new BootstrapAdminException("bootstrap administrator state is unavailable");
        }
        return state;
    }

    void markConsumed(UUID adminUserId) {
        int updated = jdbcTemplate.update(
                "UPDATE bootstrap_admin_state "
                        + "SET consumed = TRUE, consumed_at = CURRENT_TIMESTAMP, admin_user_id = ? "
                        + "WHERE id = 1 AND consumed = FALSE",
                adminUserId);
        if (updated != 1) {
            throw new BootstrapAdminException(
                    "bootstrap administrator state could not be consumed exactly once");
        }
    }

    record State(boolean consumed, UUID adminUserId) {
    }
}
