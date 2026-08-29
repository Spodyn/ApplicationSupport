package com.unifiedsupportinbox.integration.internal;

import com.unifiedsupportinbox.integration.IntegrationProvider;
import com.unifiedsupportinbox.integration.IntegrationStatus;
import com.unifiedsupportinbox.integration.ProviderIntegrationCredentialLookup;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class ProviderIntegrationCredentialLookupAdapter implements ProviderIntegrationCredentialLookup {

    private final IntegrationRepository integrations;

    ProviderIntegrationCredentialLookupAdapter(IntegrationRepository integrations) {
        this.integrations = integrations;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CredentialReference> findForProvider(IntegrationProvider provider) {
        return integrations.findAll().stream()
                .filter(integration -> integration.provider() == provider)
                .filter(integration -> integration.status() != IntegrationStatus.DISABLED)
                .filter(integration -> integration.secretRef() != null && !integration.secretRef().isBlank())
                .map(integration -> new CredentialReference(
                        integration.id(),
                        integration.workspaceExternalId(),
                        integration.secretRef()))
                .toList();
    }
}
