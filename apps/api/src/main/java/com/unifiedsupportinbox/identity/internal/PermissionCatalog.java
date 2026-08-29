package com.unifiedsupportinbox.identity.internal;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class PermissionCatalog {

    static final String MANAGE_USERS = "manage_users";

    static final List<String> ALL = List.of(
            MANAGE_USERS,
            "manage_integrations",
            "manage_sla",
            "manage_schedule",
            "manage_notifications",
            "view_global_statistics",
            "reassign_cases",
            "force_resolve",
            "view_audit");

    private static final Set<String> KNOWN = Set.copyOf(ALL);

    private PermissionCatalog() {
    }

    static List<String> normalizeRequested(List<String> requested) {
        if (requested == null) {
            throw new IllegalArgumentException("permissions are required");
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String permission : requested) {
            if (permission == null || permission.isBlank() || !KNOWN.contains(permission)) {
                throw new IllegalArgumentException("unknown permission code");
            }
            unique.add(permission);
        }
        return ALL.stream().filter(unique::contains).toList();
    }
}
