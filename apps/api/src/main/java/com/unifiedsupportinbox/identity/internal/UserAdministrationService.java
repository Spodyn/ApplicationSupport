package com.unifiedsupportinbox.identity.internal;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.audit.AuditActorType;
import com.unifiedsupportinbox.audit.AuditEventStore;
import com.unifiedsupportinbox.identity.UserRole;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class UserAdministrationService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration INVITATION_TTL = Duration.ofHours(24);
    private static final Duration RESET_TTL = Duration.ofMinutes(30);

    private final UserAccountRepository users;
    private final PermissionStore permissionStore;
    private final PermissionService permissionService;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final AuditEventStore audit;

    UserAdministrationService(
            UserAccountRepository users,
            PermissionStore permissionStore,
            PermissionService permissionService,
            JdbcTemplate jdbc,
            PasswordEncoder encoder,
            AuditEventStore audit) {
        this.users = users;
        this.permissionStore = permissionStore;
        this.permissionService = permissionService;
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    List<UserView> list(UsiSessionPrincipal actor, String search, UserRole role, Boolean active) {
        requireManager(actor);
        String term = search == null ? null : search.strip().toLowerCase(Locale.ROOT);
        return users.findAll().stream()
                .filter(user -> term == null || term.isBlank()
                        || user.email().contains(term)
                        || user.displayName().toLowerCase(Locale.ROOT).contains(term))
                .filter(user -> role == null || user.role() == role)
                .filter(user -> active == null || user.active() == active)
                .map(this::view)
                .sorted((left, right) -> left.fullName().compareToIgnoreCase(right.fullName()))
                .toList();
    }

    @Transactional(readOnly = true)
    UserView current(UsiSessionPrincipal actor) {
        requireManager(actor);
        return view(requireUser(actor.userId()));
    }

    @Transactional
    UserView create(UsiSessionPrincipal actor, UserInput input) {
        requireManager(actor);
        validateInput(input);
        if (users.findByEmailIgnoreCase(input.email()).isPresent()) {
            throw ApiProblemException.conflict("A user with this email already exists.");
        }
        UserAccount saved = users.saveAndFlush(new UserAccount(
                input.email(), input.fullName(), null, input.role(), input.active(),
                input.validFrom(), input.validUntil()));
        replacePermissions(saved, input.permissions());
        assertManagingAdministratorRemains();
        audit.append(actorType(actor), actor.userId(), "USER_CREATED", "USER", saved.id(), null,
                Map.of("role", saved.role().name(), "active", saved.active()));
        return view(saved);
    }

    @Transactional
    UserView update(UsiSessionPrincipal actor, UUID userId, UserInput input) {
        requireManager(actor);
        validateInput(input);
        lockAdministrators();
        UserAccount target = requireUser(userId);
        users.findByEmailIgnoreCase(input.email())
                .filter(other -> !other.id().equals(userId))
                .ifPresent(other -> { throw ApiProblemException.conflict("A user with this email already exists."); });
        target.updateProfile(input.email(), input.fullName(), input.role());
        target.setActive(input.active());
        target.setValidityWindow(input.validFrom(), input.validUntil());
        replacePermissions(target, input.permissions());
        users.saveAndFlush(target);
        assertManagingAdministratorRemains();
        if (!target.isSessionEligibleAt(Instant.now())) invalidateSessions(target.id());
        audit.append(actorType(actor), actor.userId(), "USER_UPDATED", "USER", target.id(), null,
                Map.of("role", target.role().name(), "active", target.active()));
        return view(target);
    }

    @Transactional
    UserView deactivate(UsiSessionPrincipal actor, UUID userId) {
        requireManager(actor);
        if (actor.userId().equals(userId)) {
            throw ApiProblemException.conflict("You cannot deactivate your own account.");
        }
        lockAdministrators();
        UserAccount target = requireUser(userId);
        target.setActive(false);
        target.setValidityWindow(target.validFrom(), Instant.now());
        users.saveAndFlush(target);
        assertManagingAdministratorRemains();
        invalidateSessions(userId);
        audit.append(actorType(actor), actor.userId(), "USER_DEACTIVATED", "USER", userId, null, Map.of());
        return view(target);
    }

    @Transactional
    void delete(UsiSessionPrincipal actor, UUID userId) {
        requireManager(actor);
        if (actor.userId().equals(userId)) throw ApiProblemException.conflict("You cannot delete your own account.");
        lockAdministrators();
        UserAccount target = requireUser(userId);
        if (assignedCases(userId) > 0) {
            throw ApiProblemException.conflict("Reassign this user's active Cases before deleting the account.");
        }
        permissionStore.replaceExplicitPermissions(userId, List.of());
        audit.append(actorType(actor), actor.userId(), "USER_DELETED", "USER", userId, null,
                Map.of("role", target.role().name()));
        try {
            users.delete(target);
            users.flush();
        } catch (DataIntegrityViolationException exception) {
            throw ApiProblemException.conflict("This account is referenced by operational history and cannot be deleted; deactivate it instead.");
        }
        assertManagingAdministratorRemains();
    }

    @Transactional
    CredentialToken issueInvitation(UsiSessionPrincipal actor, UUID userId) {
        requireManager(actor);
        UserAccount target = requireUser(userId);
        target.setPasswordHash(null);
        users.saveAndFlush(target);
        invalidateSessions(userId);
        return issueToken(actor, target, "INVITATION", INVITATION_TTL, "USER_INVITATION_CREATED");
    }

    @Transactional
    CredentialToken issuePasswordReset(UsiSessionPrincipal actor, UUID userId) {
        requireManager(actor);
        return issueToken(actor, requireUser(userId), "PASSWORD_RESET", RESET_TTL, "USER_PASSWORD_RESET_CREATED");
    }

    @Transactional
    void requestPasswordReset(String email) {
        users.findByEmailIgnoreCase(email == null ? "" : email.strip()).ifPresent(user -> {
            if (user.active()) issueToken(null, user, "PASSWORD_RESET", RESET_TTL, "USER_PASSWORD_RESET_REQUESTED");
        });
    }

    @Transactional
    void consumeToken(String rawToken, String rawPassword, String expectedType) {
        if (rawToken == null || rawToken.isBlank()) throw ApiProblemException.validationFailed("The action token is invalid.");
        char[] password = rawPassword == null ? new char[0] : rawPassword.toCharArray();
        try {
            PasswordPolicy.validateForStorage(password);
            TokenRow token = jdbc.query("""
                    SELECT id, user_id FROM account_action_tokens
                    WHERE token_hash = ? AND token_type = ? AND used_at IS NULL AND expires_at > now()
                    FOR UPDATE
                    """, (rs, row) -> new TokenRow(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class)),
                    hash(rawToken), expectedType).stream().findFirst()
                    .orElseThrow(() -> ApiProblemException.validationFailed("The action token is invalid or expired."));
            UserAccount user = requireUser(token.userId());
            user.setPasswordHash(encoder.encode(CharBuffer.wrap(password)));
            user.setActive(true);
            users.saveAndFlush(user);
            jdbc.update("UPDATE account_action_tokens SET used_at = now() WHERE user_id = ? AND used_at IS NULL", user.id());
            invalidateSessions(user.id());
        } catch (IllegalArgumentException exception) {
            throw ApiProblemException.validationFailed(exception.getMessage());
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }

    private CredentialToken issueToken(UsiSessionPrincipal actor, UserAccount user, String type, Duration ttl, String auditAction) {
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        Instant expiresAt = Instant.now().plus(ttl);
        jdbc.update("UPDATE account_action_tokens SET used_at = now() WHERE user_id = ? AND token_type = ? AND used_at IS NULL", user.id(), type);
        jdbc.update("INSERT INTO account_action_tokens (user_id, token_type, token_hash, expires_at, created_by) VALUES (?, ?, ?, ?, ?)",
                user.id(), type, hash(raw), expiresAt, actor == null ? null : actor.userId());
        audit.append(actor == null ? AuditActorType.SYSTEM : actorType(actor), actor == null ? null : actor.userId(),
                auditAction, "USER", user.id(), null, Map.of("expiresAt", expiresAt.toString()));
        return new CredentialToken(raw, expiresAt);
    }

    private UserView view(UserAccount user) {
        return new UserView(user.id(), user.displayName(), user.email(), user.role(), user.active(),
                user.validFrom(), user.validUntil(), user.lastLoginAt(), presence(user.id()), assignedCases(user.id()),
                permissionService.effectivePermissions(user));
    }

    private String presence(UUID userId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE principal_name = ? AND expiry_time > ?",
                Integer.class, userId.toString(), System.currentTimeMillis());
        return count != null && count > 0 ? "online" : "offline";
    }

    private int assignedCases(UUID userId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM cases WHERE owner_user_id = ? AND status = 'VERIFICATION'", Integer.class, userId);
        return count == null ? 0 : count;
    }

    private void replacePermissions(UserAccount user, List<String> requested) {
        List<String> normalized;
        try {
            normalized = PermissionCatalog.normalizeRequested(requested == null ? List.of() : requested);
        } catch (IllegalArgumentException exception) {
            throw ApiProblemException.validationFailed("One or more permission codes are invalid.");
        }
        permissionStore.replaceExplicitPermissions(user.id(), user.role() == UserRole.ADMIN ? normalized : List.of());
    }

    private void validateInput(UserInput input) {
        if (input == null || input.email() == null || input.fullName() == null || input.role() == null) {
            throw ApiProblemException.validationFailed("Name, email and role are required.");
        }
        if (input.validFrom() != null && input.validUntil() != null && !input.validFrom().isBefore(input.validUntil())) {
            throw ApiProblemException.validationFailed("validFrom must be before validUntil.");
        }
    }

    private void requireManager(UsiSessionPrincipal actor) {
        permissionService.requirePermission(actor, PermissionCatalog.MANAGE_USERS);
    }

    private UserAccount requireUser(UUID id) {
        return users.findById(id).orElseThrow(() -> ApiProblemException.notFound("User was not found."));
    }

    private void lockAdministrators() {
        jdbc.queryForList("SELECT id FROM users WHERE role = 'ADMIN' FOR UPDATE", UUID.class);
    }

    private void assertManagingAdministratorRemains() {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM users u JOIN user_permissions p ON p.user_id = u.id
                WHERE u.role = 'ADMIN' AND u.active = TRUE AND p.permission_code = 'manage_users'
                  AND (u.valid_from IS NULL OR u.valid_from <= now())
                  AND (u.valid_until IS NULL OR u.valid_until > now())
                """, Integer.class);
        if (count == null || count == 0) throw ApiProblemException.conflict("At least one active administrator with user-management permission is required.");
    }

    private void invalidateSessions(UUID userId) {
        jdbc.update("DELETE FROM spring_session WHERE principal_name = ?", userId.toString());
    }

    private static AuditActorType actorType(UsiSessionPrincipal actor) {
        return actor.role() == UserRole.ADMIN ? AuditActorType.ADMIN : AuditActorType.USER;
    }

    private static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    record UserInput(String fullName, String email, UserRole role, boolean active,
                     Instant validFrom, Instant validUntil, List<String> permissions) { }
    record UserView(UUID id, String fullName, String email, UserRole role, boolean active,
                    Instant validFrom, Instant validUntil, Instant lastLoginAt, String presence,
                    int activeAssignedCases, List<String> permissions) { }
    record CredentialToken(String token, Instant expiresAt) { }
    private record TokenRow(UUID id, UUID userId) { }
}
