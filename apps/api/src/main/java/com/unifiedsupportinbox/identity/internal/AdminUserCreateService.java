package com.unifiedsupportinbox.identity.internal;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.IdempotencyResponse;
import com.unifiedsupportinbox.IdempotencyResult;
import com.unifiedsupportinbox.IdempotentCommandExecutor;
import com.unifiedsupportinbox.audit.AuditActorType;
import com.unifiedsupportinbox.audit.AuditEventStore;
import com.unifiedsupportinbox.identity.UserRole;
import java.nio.CharBuffer;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Creates an account and its grants in one replay-safe transaction. The initial
 * password path is independent of the later optional invitation flow (USI-66).
 */
@Service
class AdminUserCreateService {

    private static final String SCOPE = "admin.users.create";

    private final UserAccountRepository users;
    private final PermissionStore permissionStore;
    private final PermissionService permissionService;
    private final PasswordEncoder encoder;
    private final AuditEventStore audit;
    private final IdempotentCommandExecutor idempotency;
    private final ObjectMapper json;

    AdminUserCreateService(
            UserAccountRepository users,
            PermissionStore permissionStore,
            PermissionService permissionService,
            PasswordEncoder encoder,
            AuditEventStore audit,
            IdempotentCommandExecutor idempotency,
            ObjectMapper json) {
        this.users = users;
        this.permissionStore = permissionStore;
        this.permissionService = permissionService;
        this.encoder = encoder;
        this.audit = audit;
        this.idempotency = idempotency;
        this.json = json;
    }

    IdempotencyResult create(UsiSessionPrincipal actor, AdminUserCreateRequest request, String key) {
        if (actor == null || actor.role() != UserRole.ADMIN) {
            throw ApiProblemException.accessDenied();
        }
        permissionService.requirePermission(actor, PermissionCatalog.MANAGE_USERS);
        if (request == null || request.role() == null || request.active() == null) {
            throw ApiProblemException.validationFailed("Email, name, role and active flag are required.");
        }

        String email = normalizeEmail(request.email());
        String fullName = normalizeName(request.fullName());
        if (request.validFrom() != null && request.validUntil() != null
                && request.validFrom().isAfter(request.validUntil())) {
            throw ApiProblemException.validationFailed("Validity start must be before validity end.");
        }
        if (request.validUntil() != null && !request.validUntil().isAfter(Instant.now())
                && request.active()) {
            throw ApiProblemException.validationFailed("An active user cannot already be expired.");
        }

        // USI-66 owns token issuance for the optional no-password path. Do not
        // create an accidentally unusable account with a null/placeholder hash.
        String initialPassword = request.initialPassword();
        if (initialPassword == null) {
            throw ApiProblemException.validationFailed(
                    "Initial password is required until the invitation flow is available.");
        }
        char[] password = initialPassword.toCharArray();
        try {
            try {
                PasswordPolicy.validateForStorage(password);
            } catch (IllegalArgumentException invalid) {
                throw ApiProblemException.validationFailed("Password must contain 12 to 128 valid Unicode characters.");
            }

            List<String> grants = grants(request.role(), request.permissions());
            Map<String, Object> fingerprint = new LinkedHashMap<>();
            fingerprint.put("email", email);
            fingerprint.put("fullName", fullName);
            fingerprint.put("role", request.role().name());
            fingerprint.put("active", request.active());
            fingerprint.put("validFrom", request.validFrom());
            fingerprint.put("validUntil", request.validUntil());
            fingerprint.put("permissions", grants);
            fingerprint.put("initialPassword", initialPassword);

            return idempotency.execute(actor.userId(), SCOPE, key, fingerprint,
                    () -> persist(actor, email, fullName, request, grants, password));
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private IdempotencyResponse persist(
            UsiSessionPrincipal actor,
            String email,
            String fullName,
            AdminUserCreateRequest input,
            List<String> grants,
            char[] password) {
        // Encoding occurs only inside the transaction and never becomes an audit field.
        String hash = encoder.encode(CharBuffer.wrap(password));
        UserAccount user;
        try {
            user = users.saveAndFlush(new UserAccount(
                    email, fullName, hash, input.role(), input.active(),
                    input.validFrom(), input.validUntil()));
            permissionStore.replaceExplicitPermissions(user.id(), grants);
        } catch (DataIntegrityViolationException duplicate) {
            throw ApiProblemException.conflict("A user with this email already exists.");
        }

        audit.append(AuditActorType.ADMIN, actor.userId(), "USER_CREATED", "USER",
                user.id(), null, Map.of(
                        "role", user.role().name(),
                        "active", user.active(),
                        "permissionCodes", grants,
                        "creationMethod", "INITIAL_PASSWORD"));
        ObjectNode response = json.createObjectNode();
        response.put("id", user.id().toString());
        response.put("email", user.email());
        response.put("fullName", user.displayName());
        response.put("role", user.role().name());
        response.put("active", user.active());
        response.put("credentialState", "PASSWORD_SET");
        response.put("version", user.version());
        if (user.validFrom() != null) response.put("validFrom", user.validFrom().toString());
        if (user.validUntil() != null) response.put("validUntil", user.validUntil().toString());
        return new IdempotencyResponse(201, response);
    }

    private static List<String> grants(UserRole role, List<String> requested) {
        List<String> grants;
        try {
            grants = requested == null
                    ? role == UserRole.ADMIN ? PermissionCatalog.ALL : List.of()
                    : PermissionCatalog.normalizeRequested(requested);
        } catch (IllegalArgumentException invalid) {
            throw ApiProblemException.validationFailed("One or more permission codes are invalid.");
        }
        // Until USI-73 distinguishes default-full from an intentionally reduced
        // ADMIN grant set, do not persist a misleading apparent restriction.
        // Some existing administrative endpoints currently check only a permission
        // authority. Prevent an ADMIN permission from being attached to a USER
        // through account creation; USI-73 owns the broader RBAC migration.
        if (role == UserRole.USER && !grants.isEmpty()) {
            throw ApiProblemException.validationFailed(
                    "USER accounts cannot receive administrative permission grants.");
        }
        if (role == UserRole.ADMIN && !grants.containsAll(PermissionCatalog.ALL)) {
            throw ApiProblemException.validationFailed(
                    "New administrators must start with the default full permission set.");
        }
        return grants;
    }

    private static String normalizeEmail(String value) {
        if (value == null) throw ApiProblemException.validationFailed("Email is required.");
        String email = value.strip().toLowerCase(Locale.ROOT);
        if (email.length() < 3 || email.length() > 320
                || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
            throw ApiProblemException.validationFailed("Email address is invalid.");
        }
        return email;
    }

    private static String normalizeName(String value) {
        if (value == null || value.strip().isEmpty() || value.strip().length() > 200) {
            throw ApiProblemException.validationFailed("Full name must contain 1 to 200 characters.");
        }
        return value.strip();
    }
}
