package com.unifiedsupportinbox.integration.internal;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.audit.AuditActors;
import com.unifiedsupportinbox.audit.AuditEventStore;
import com.unifiedsupportinbox.integration.IntegrationConnectionTestView;
import com.unifiedsupportinbox.integration.IntegrationConnectionTester;
import com.unifiedsupportinbox.integration.IntegrationHealth;
import com.unifiedsupportinbox.integration.IntegrationProvider;
import com.unifiedsupportinbox.integration.IntegrationStatus;
import com.unifiedsupportinbox.integration.IntegrationView;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class IntegrationService {

    private static final String MANAGE_INTEGRATIONS = "manage_integrations";
    private static final Pattern SAFE_SECRET_REFERENCE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,511}");
    private static final Pattern SAFE_PROVIDER_ERROR_CODE = Pattern.compile("[A-Z][A-Z0-9_.:]{0,127}");

    private final IntegrationRepository integrations;
    private final List<IntegrationConnectionTester> connectionTesters;
    private final AuditEventStore auditEvents;
    private final IntegrationConnectionTestRunner connectionTestRunner;

    IntegrationService(
            IntegrationRepository integrations,
            List<IntegrationConnectionTester> connectionTesters,
            AuditEventStore auditEvents,
            IntegrationConnectionTestRunner connectionTestRunner) {
        this.integrations = integrations;
        this.connectionTesters = List.copyOf(connectionTesters);
        this.auditEvents = auditEvents;
        this.connectionTestRunner = connectionTestRunner;
    }

    @Transactional(readOnly = true)
    List<IntegrationView> list(Authentication actor) {
        requireManageIntegrations(actor);
        return integrations.findAll().stream().map(IntegrationRecord::toView).toList();
    }

    @Transactional(readOnly = true)
    IntegrationView get(Authentication actor, UUID integrationId) {
        requireManageIntegrations(actor);
        return integrations.findById(integrationId)
                .map(IntegrationRecord::toView)
                .orElseThrow(() -> ApiProblemException.notFound("Integration was not found."));
    }

    @Transactional
    IntegrationView create(Authentication actor, IntegrationConfigurationInput input) {
        requireManageIntegrations(actor);
        IntegrationConfigurationInput normalized = normalize(input);
        try {
            IntegrationRecord created = integrations.create(
                            normalized.provider(),
                            normalized.displayName(),
                            IntegrationStatus.CONFIGURING,
                            IntegrationHealth.UNKNOWN,
                            normalized.workspaceExternalId(),
                            normalized.workspaceName(),
                            normalized.secretRef(),
                            "{}");
            appendAudit(actor, created, "INTEGRATION_CREATED", Map.of());
            return created.toView();
        } catch (DataIntegrityViolationException exception) {
            throw ApiProblemException.conflict("An active integration already exists for this workspace.");
        } catch (IllegalArgumentException exception) {
            throw ApiProblemException.validationFailed(exception.getMessage());
        }
    }

    @Transactional
    IntegrationView update(Authentication actor, UUID integrationId, IntegrationConfigurationInput input) {
        requireManageIntegrations(actor);
        IntegrationRecord current = integrations.findById(integrationId)
                .orElseThrow(() -> ApiProblemException.notFound("Integration was not found."));
        IntegrationConfigurationInput normalized = normalize(input);
        if (current.provider() != normalized.provider()) {
            throw ApiProblemException.validationFailed("Integration provider cannot be changed.");
        }
        try {
            IntegrationRecord updated = integrations.updateConfiguration(
                            integrationId,
                            normalized.displayName(),
                            normalized.workspaceExternalId(),
                            normalized.workspaceName(),
                            normalized.secretRef());
            appendAudit(actor, updated, "INTEGRATION_CONFIGURATION_UPDATED", Map.of());
            return updated.toView();
        } catch (DataIntegrityViolationException exception) {
            throw ApiProblemException.conflict("An active integration already exists for this workspace.");
        } catch (IllegalArgumentException exception) {
            throw ApiProblemException.validationFailed(exception.getMessage());
        }
    }

    @Transactional
    IntegrationView setStatus(Authentication actor, UUID integrationId, IntegrationStatus status) {
        requireManageIntegrations(actor);
        if (status == null || status == IntegrationStatus.CONFIGURING) {
            throw ApiProblemException.validationFailed("Status must be ENABLED or DISABLED.");
        }
        IntegrationRecord current = integrations.findById(integrationId)
                .orElseThrow(() -> ApiProblemException.notFound("Integration was not found."));
        if (status == IntegrationStatus.ENABLED && current.secretRef() == null) {
            throw ApiProblemException.validationFailed("Integration credentials must be configured before enabling it.");
        }
        IntegrationRecord updated;
        try {
            updated = integrations.updateStatus(integrationId, status);
        } catch (DataIntegrityViolationException conflict) {
            throw ApiProblemException.conflict("Integration status conflicts with the active provider/workspace mapping.");
        }
        appendAudit(actor, updated, status == IntegrationStatus.ENABLED
                ? "INTEGRATION_ENABLED"
                : "INTEGRATION_DISABLED", Map.of("status", status.name()));
        return updated.toView();
    }

    IntegrationConnectionTestView testConnection(Authentication actor, UUID integrationId) {
        requireManageIntegrations(actor);
        IntegrationRecord integration = integrations.findById(integrationId)
                .orElseThrow(() -> ApiProblemException.notFound("Integration was not found."));

        List<IntegrationConnectionTester> matching = connectionTesters.stream()
                .filter(tester -> tester.supports(integration.provider()))
                .toList();
        IntegrationConnectionTester.Result result = matching.size() == 1
                ? connectionTestRunner.run(matching.getFirst(), integration)
                : IntegrationConnectionTester.Result.unavailable();
        IntegrationHealth health = switch (result.outcome()) {
            case SUCCESS -> IntegrationHealth.HEALTHY;
            case TIMEOUT -> IntegrationHealth.DEGRADED;
            case UNAUTHORIZED, UNAVAILABLE -> IntegrationHealth.UNAVAILABLE;
        };
        String errorCode = result.outcome() == IntegrationConnectionTester.Outcome.SUCCESS
                ? null
                : normalizeErrorCode(result.errorCode(), result.outcome());
        IntegrationRecord updated = integrations.updateHealthPreservingLastEvent(
                integration.id(), health, errorCode);
        return new IntegrationConnectionTestView(updated.toView(), result.outcome(), errorCode);
    }

    private static void requireManageIntegrations(Authentication actor) {
        if (!hasAuthority(actor, "ROLE_ADMIN") || !hasAuthority(actor, MANAGE_INTEGRATIONS)) throw ApiProblemException.accessDenied();
    }

    private static IntegrationConfigurationInput normalize(IntegrationConfigurationInput input) {
        if (input == null || input.provider() == null) {
            throw ApiProblemException.validationFailed("Integration provider is required.");
        }
        String displayName = requiredText(input.displayName(), "Display name");
        String workspaceExternalId = requiredText(input.workspaceExternalId(), "Workspace ID");
        String workspaceName = requiredText(input.workspaceName(), "Workspace name");
        String secretRef = requiredText(input.secretRef(), "Secret reference");
        if (!safeSecretReference(secretRef)) {
            throw ApiProblemException.validationFailed("Secret reference must be a safe relative path below the configured secret directory.");
        }
        return new IntegrationConfigurationInput(
                input.provider(), displayName, workspaceExternalId, workspaceName, secretRef);
    }

    private static String requiredText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw ApiProblemException.validationFailed(label + " is required.");
        }
        return value.strip();
    }

    private static boolean safeSecretReference(String value) {
        if (!SAFE_SECRET_REFERENCE.matcher(value).matches() || value.contains("\\")) return false;
        try {
            Path path = Path.of(value);
            if (path.isAbsolute()) return false;
            for (Path segment : path) {
                if (".".equals(segment.toString()) || "..".equals(segment.toString())) return false;
            }
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static String normalizeErrorCode(
            String value, IntegrationConnectionTester.Outcome outcome) {
        String fallback = switch (outcome) {
            case SUCCESS -> null;
            case TIMEOUT -> "TEST_CONNECTION_TIMEOUT";
            case UNAUTHORIZED -> "PROVIDER_UNAUTHORIZED";
            case UNAVAILABLE -> "PROVIDER_UNAVAILABLE";
        };
        if (value == null || value.isBlank()) return fallback;
        String normalized = value.strip();
        return SAFE_PROVIDER_ERROR_CODE.matcher(normalized).matches() ? normalized : fallback;
    }

    private static boolean hasAuthority(Authentication actor, String expected) {
        return actor != null
                && actor.isAuthenticated()
                && actor.getAuthorities().stream()
                        .anyMatch(authority -> expected.equals(authority.getAuthority()));
    }

    private void appendAudit(
            Authentication actor,
            IntegrationRecord integration,
            String action,
            Map<String, ?> extra) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", integration.provider().name());
        metadata.put("workspaceExternalId", integration.workspaceExternalId());
        metadata.put("secretConfigured", integration.secretRef() != null);
        metadata.putAll(extra);
        auditEvents.append(
                AuditActors.type(actor),
                AuditActors.userId(actor),
                action,
                "INTEGRATION",
                integration.id(),
                null,
                metadata);
    }

    record IntegrationConfigurationInput(
            IntegrationProvider provider,
            String displayName,
            String workspaceExternalId,
            String workspaceName,
            String secretRef) {
    }
}
