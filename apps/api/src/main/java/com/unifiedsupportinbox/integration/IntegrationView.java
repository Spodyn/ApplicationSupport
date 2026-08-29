package com.unifiedsupportinbox.integration;

import java.time.Instant;
import java.util.UUID;

/**
 * Secret-safe provider installation projection exposed outside the integration module.
 *
 * <p>The external secret reference and provider-specific configuration document are
 * deliberately absent. Callers only learn whether credentials have been configured.</p>
 */
public record IntegrationView(
        UUID id,
        IntegrationProvider provider,
        String displayName,
        IntegrationStatus status,
        IntegrationHealth health,
        String workspaceExternalId,
        String workspaceName,
        boolean secretConfigured,
        Instant lastEventAt,
        String lastErrorCode,
        Instant createdAt,
        Instant updatedAt) {
}
