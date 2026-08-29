package com.unifiedsupportinbox.identity.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class PermissionStore {

    private final JdbcTemplate jdbc;

    PermissionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<String> explicitPermissions(UUID userId) {
        return jdbc.queryForList(
                "SELECT permission_code FROM user_permissions WHERE user_id = ? ORDER BY permission_code",
                String.class,
                userId);
    }

    void replaceExplicitPermissions(UUID userId, List<String> permissionCodes) {
        jdbc.update("DELETE FROM user_permissions WHERE user_id = ?", userId);
        for (String code : permissionCodes) {
            jdbc.update(
                    "INSERT INTO user_permissions (user_id, permission_code) VALUES (?, ?)",
                    userId,
                    code);
        }
    }
}
