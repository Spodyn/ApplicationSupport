package com.unifiedsupportinbox.integration.internal;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.integration.IntegrationConnectionTestView;
import com.unifiedsupportinbox.integration.IntegrationConnectionTester;
import com.unifiedsupportinbox.integration.IntegrationHealth;
import com.unifiedsupportinbox.integration.IntegrationStatus;
import com.unifiedsupportinbox.integration.IntegrationView;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class IntegrationService {

    private static final String MANAGE_INTEGRATIONS = "manage_integrations";

    private final IntegrationRepository integrations;
    private final List<IntegrationConnectionTester> connectionTesters;

    IntegrationService(
            IntegrationRepository integrations,
            List<IntegrationConnectionTester> connectionTesters) {
        this.integrations = integrations;
        this.connectionTesters = List.copyOf(connectionTesters);
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
    IntegrationView setStatus(Authentication actor, UUID integrationId, IntegrationStatus status) {
        requireManageIntegrations(actor);
        if (status == null || status == IntegrationStatus.CONFIGURING) {
            throw ApiProblemException.validationFailed("Common status update supports only ENABLED or DISABLED.");
        }
        integrations.findById(integrationId)
                .orElseThrow(() -> ApiProblemException.notFound("Integration was not found."));
        try {
            return integrations.updateStatus(integrationId, status).toView();
        } catch (org.springframework.dao.DataIntegrityViolationException conflict) {
            throw ApiProblemException.conflict("Integration status conflicts with the active provider/workspace mapping.");
        }
    }

    @Transactional
    IntegrationView setStatus(Authentication actor, UUID integrationId, com.unifiedsupportinbox.integration.IntegrationStatus status) {
        requireManageIntegrations(actor);
        if (status == null || status == com.unifiedsupportinbox.integration.IntegrationStatus.CONFIGURING) {
            throw ApiProblemException.validationFailed("status must be ENABLED or DISABLED.");
        }
        try {
            return integrations.updateStatus(integrationId, status).toView();
        } catch (IllegalArgumentException exception) {
            throw ApiProblemException.notFound("Integration was not found.");
        } catch (org.springframework.dao.DataIntegrityViolationException exception) {
            throw ApiProblemException.conflict("Integration status conflicts with an existing active provider mapping.");
        }
    }

    @Transactional
    IntegrationConnectionTestView testConnection(Authentication actor, UUID integrationId) {
        requireManageIntegrations(actor);
        IntegrationRecord integration = integrations.findById(integrationId)
                .orElseThrow(() -> ApiProblemException.notFound("Integration was not found."));

        List<IntegrationConnectionTester> matching = connectionTesters.stream()
                .filter(tester -> tester.supports(integration.provider()))
                .toList();
        IntegrationConnectionTester.Result result = matching.size() == 1
                ? runTest(matching.getFirst(), integration)
                : IntegrationConnectionTester.Result.unavailable();
        IntegrationHealth health = switch (result.outcome()) {
            case SUCCESS -> IntegrationHealth.HEALTHY;
            case TIMEOUT -> IntegrationHealth.DEGRADED;
            case UNAUTHORIZED, UNAVAILABLE -> IntegrationHealth.UNAVAILABLE;
        };
        String errorCode = switch (result.outcome()) {
            case SUCCESS -> null;
            case TIMEOUT -> "TEST_CONNECTION_TIMEOUT";
            case UNAUTHORIZED -> "PROVIDER_UNAUTHORIZED";
            case UNAVAILABLE -> "PROVIDER_UNAVAILABLE";
        };
        IntegrationRecord updated = integrations.updateHealth(
                integration.id(), health, integration.lastEventAt(), errorCode);
        return new IntegrationConnectionTestView(updated.toView(), result.outcome(), errorCode);
    }

    private static IntegrationConnectionTester.Result runTest(
            IntegrationConnectionTester tester, IntegrationRecord integration) {
        try {
            IntegrationConnectionTester.Result result = tester.test(new IntegrationConnectionTester.Request(
                    integration.id(), integration.provider(), integration.workspaceExternalId(), integration.secretRef() != null));
            return result == null || result.outcome() == null
                    ? IntegrationConnectionTester.Result.unavailable()
                    : result;
        } catch (RuntimeException ignored) {
            return IntegrationConnectionTester.Result.unavailable();
        }
    }

    private static void requireManageIntegrations(Authentication actor) {
        if (!hasAuthority(actor, MANAGE_INTEGRATIONS)) throw ApiProblemException.accessDenied();
    }

    private static boolean hasAuthority(Authentication actor, String expected) {
        return actor != null
                && actor.isAuthenticated()
                && actor.getAuthorities().stream()
                        .anyMatch(authority -> expected.equals(authority.getAuthority()));
    }
}
