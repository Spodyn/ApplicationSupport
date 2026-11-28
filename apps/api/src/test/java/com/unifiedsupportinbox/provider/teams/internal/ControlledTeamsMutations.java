package com.unifiedsupportinbox.provider.teams.internal;

import com.unifiedsupportinbox.integration.ProviderIntegrationCredentialLookup;
import com.unifiedsupportinbox.messaging.MessageDeliveryProvider;
import com.unifiedsupportinbox.provider.internal.ConfiguredProviderSecretResolver;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Isolated outbound fixture credentials do not alter onboarding/global provider settings. */
@TestConfiguration(proxyBeanMethods=false)
public class ControlledTeamsMutations {
    public static final String BOT="11111111-1111-4111-8111-111111111111";
    public static final String TENANT="22222222-2222-4222-8222-222222222222";
    @Bean MessageDeliveryProvider controlledTeamsDelivery(ProviderIntegrationCredentialLookup integrations,
            ConfiguredProviderSecretResolver secrets,TeamsRouteStore routes,TeamsConnectorClient connector,
            TeamsOutboundAttachments files,TeamsOutboundAcceptance acceptance) {
        return new TeamsMessageDeliveryProvider(integrations,secrets,routes,connector,BOT,TENANT,files,acceptance);
    }
}
