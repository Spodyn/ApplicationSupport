package com.unifiedsupportinbox.identity.internal;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.audit.AuditActorType;
import com.unifiedsupportinbox.audit.AuditEventStore;
import com.unifiedsupportinbox.identity.UserRole;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/settings")
class OrganizationSettingsController {

    private static final UUID SETTINGS_AUDIT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final JdbcTemplate jdbc;
    private final PermissionService permissions;
    private final AuditEventStore audit;

    OrganizationSettingsController(JdbcTemplate jdbc, PermissionService permissions, AuditEventStore audit) {
        this.jdbc = jdbc;
        this.permissions = permissions;
        this.audit = audit;
    }

    @GetMapping
    @Transactional(readOnly = true)
    SettingsView get(Authentication authentication) {
        UsiSessionPrincipal actor = administrator(authentication);
        return read();
    }

    @PutMapping("/general")
    @Transactional
    SettingsView saveGeneral(@RequestBody GeneralInput input, Authentication authentication) {
        UsiSessionPrincipal actor = administrator(authentication);
        if (input == null || input.organizationName() == null || input.organizationName().isBlank()
                || input.organizationName().strip().length() > 200 || !"pl".equals(input.interfaceLanguage())) {
            throw ApiProblemException.validationFailed("Organization settings are invalid.");
        }
        jdbc.update("""
                UPDATE organization_settings SET organization_name = ?, interface_language = ?, compact_mode = ?,
                    updated_by = ?, updated_at = now() WHERE singleton = TRUE
                """, input.organizationName().strip(), input.interfaceLanguage(), input.compactMode(), actor.userId());
        appendAudit(actor, "GENERAL_SETTINGS_UPDATED");
        return read();
    }

    @PutMapping("/role-permissions")
    @Transactional
    SettingsView saveRolePermissions(@RequestBody RolePermissionsInput input, Authentication authentication) {
        UsiSessionPrincipal actor = administrator(authentication);
        permissions.requirePermission(actor, PermissionCatalog.MANAGE_USERS);
        List<String> normalized;
        try {
            normalized = PermissionCatalog.normalizeRequested(input == null ? null : input.adminPermissions());
        } catch (IllegalArgumentException exception) {
            throw ApiProblemException.validationFailed("One or more permission codes are invalid.");
        }
        jdbc.update("""
                UPDATE organization_settings SET admin_default_permissions = ?::text[], updated_by = ?, updated_at = now()
                WHERE singleton = TRUE
                """, "{" + String.join(",", normalized) + "}", actor.userId());
        appendAudit(actor, "ROLE_PERMISSION_DEFAULTS_UPDATED");
        return read();
    }

    @PutMapping("/sla-display")
    @Transactional
    SettingsView saveSlaDisplay(@RequestBody SlaDisplayInput input, Authentication authentication) {
        UsiSessionPrincipal actor = administrator(authentication);
        permissions.requirePermission(actor, "manage_sla");
        if (input == null || input.warningBeforeDeadlineMinutes() < 0) {
            throw ApiProblemException.validationFailed("SLA display settings are invalid.");
        }
        jdbc.update("""
                UPDATE organization_settings SET sla_warning_before_minutes = ?, sla_business_hours_only = ?,
                    updated_by = ?, updated_at = now() WHERE singleton = TRUE
                """, input.warningBeforeDeadlineMinutes(), input.businessHoursOnly(), actor.userId());
        appendAudit(actor, "SLA_DISPLAY_SETTINGS_UPDATED");
        return read();
    }

    private SettingsView read() {
        return jdbc.queryForObject("""
                SELECT organization_name, interface_language, compact_mode, admin_default_permissions,
                       sla_warning_before_minutes, sla_business_hours_only
                FROM organization_settings WHERE singleton = TRUE
                """, (rs, row) -> new SettingsView(
                new GeneralView(rs.getString("organization_name"), rs.getString("interface_language"), rs.getBoolean("compact_mode")),
                List.of(new RolePermissionsView("USER", List.of()),
                        new RolePermissionsView("ADMIN", List.of((String[]) rs.getArray("admin_default_permissions").getArray()))),
                rs.getInt("sla_warning_before_minutes"), rs.getBoolean("sla_business_hours_only")));
    }

    private UsiSessionPrincipal administrator(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof UsiSessionPrincipal actor)) {
            throw ApiProblemException.authenticationRequired();
        }
        if (actor.role() != UserRole.ADMIN) throw ApiProblemException.accessDenied();
        return actor;
    }

    private void appendAudit(UsiSessionPrincipal actor, String action) {
        audit.append(AuditActorType.ADMIN, actor.userId(), action, "SETTINGS", SETTINGS_AUDIT_ID, null, Map.of());
    }

    record GeneralInput(String organizationName, String interfaceLanguage, boolean compactMode) { }
    record RolePermissionsInput(List<String> adminPermissions) { }
    record SlaDisplayInput(int warningBeforeDeadlineMinutes, boolean businessHoursOnly) { }
    record GeneralView(String organizationName, String interfaceLanguage, boolean compactMode) { }
    record RolePermissionsView(String role, List<String> permissions) { }
    record SettingsView(GeneralView general, List<RolePermissionsView> rolePermissions,
                        int warningBeforeDeadlineMinutes, boolean businessHoursOnly) { }
}
