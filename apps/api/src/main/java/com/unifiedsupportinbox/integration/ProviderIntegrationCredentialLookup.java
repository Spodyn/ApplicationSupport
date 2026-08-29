package com.unifiedsupportinbox.integration;

import java.util.List;
import java.util.UUID;

/**
 * Server-side lookup used by provider adapters to authenticate inbound traffic.
 * The returned reference is an opaque locator only; resolved credential values
 * never cross the Integration module boundary.
 */
public interface ProviderIntegrationCredentialLookup {

    List<CredentialReference> findForProvider(IntegrationProvider provider);

    record CredentialReference(
            UUID integrationId,
            String workspaceExternalId,
            String secretRef) {
    }
}
