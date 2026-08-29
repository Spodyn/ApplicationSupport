package com.unifiedsupportinbox.integration.internal;

import com.unifiedsupportinbox.integration.IntegrationHealth;
import com.unifiedsupportinbox.integration.IntegrationProvider;
import com.unifiedsupportinbox.integration.IntegrationStatus;
import com.unifiedsupportinbox.integration.IntegrationView;
import java.time.Instant;
import java.util.UUID;

record IntegrationRecord(
        UUID id,
        IntegrationProvider provider,
        String displayName,
        IntegrationStatus status,
        IntegrationHealth health,
        String workspaceExternalId,
        String workspaceName,
        String secretRef,
        String configJson,
        Instant lastEventAt,
        String lastErrorCode,
        Instant createdAt,
        Instant updatedAt) {

    IntegrationView toView() {
        return new IntegrationView(
                id,
                provider,
                displayName,
                status,
                health,
                workspaceExternalId,
                workspaceName,
                secretRef != null,
                lastEventAt,
                lastErrorCode,
                createdAt,
                updatedAt);
    }
}
