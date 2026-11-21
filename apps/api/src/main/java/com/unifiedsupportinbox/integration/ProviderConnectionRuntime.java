package com.unifiedsupportinbox.integration;

/** Nonsecret deployment identifiers; credential references remain server-side. */
public interface ProviderConnectionRuntime {
    IntegrationProvider provider();
    record Runtime(String callbackUrl, String clientId, String tenantId, String secretRef, boolean configured) { }
    Runtime runtime(java.util.UUID integrationId);
}
