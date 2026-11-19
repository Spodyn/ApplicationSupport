package com.unifiedsupportinbox.provider.teams.internal;

import com.unifiedsupportinbox.integration.*;
import com.unifiedsupportinbox.provider.ProviderAttachmentException;
import com.unifiedsupportinbox.provider.internal.ConfiguredProviderSecretResolver;
import java.net.URI;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Credentials remain in the existing secret_ref store; tokens never enter transfer state. */
@Component
class TeamsAccessTokens {
    private final ProviderIntegrationCredentialLookup integrations;
    private final ConfiguredProviderSecretResolver secrets;
    private final TeamsConnectorClient connector;
    private final String client, tenant;
    TeamsAccessTokens(ProviderIntegrationCredentialLookup integrations, ConfiguredProviderSecretResolver secrets,
            TeamsConnectorClient connector, @Value("${usi.providers.teams.client-id:}") String client,
            @Value("${usi.providers.teams.tenant-id:}") String tenant) {
        this.integrations=integrations;this.secrets=secrets;this.connector=connector;this.client=client;this.tenant=tenant;
    }
    String token(UUID integration, String scope) {
        var credential=integrations.findEnabledForProvider(IntegrationProvider.TEAMS).stream()
                .filter(i->integration.equals(i.integrationId()) && tenant.equals(i.workspaceExternalId())).findFirst()
                .orElseThrow(()->ProviderAttachmentException.authentication("TEAMS_AUTH_FAILED"));
        byte[] secret=secrets.resolve(credential.secretRef(),"teams-client-secret")
                .orElseThrow(()->ProviderAttachmentException.authentication("TEAMS_CLIENT_SECRET_MISSING"));
        try {
            return connector.token(URI.create("https://login.microsoftonline.com/"+UUID.fromString(tenant)+"/oauth2/v2.0/token"),client,secret,scope);
        } catch (TeamsConnectorClient.TokenUnavailable failure) {
            if(failure.retryable) throw ProviderAttachmentException.unavailable("TEAMS_TOKEN_UNAVAILABLE",null);
            throw ProviderAttachmentException.authentication("TEAMS_AUTH_FAILED");
        } finally {Arrays.fill(secret,(byte)0);}
    }
}
