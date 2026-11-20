package com.unifiedsupportinbox.provider.slack.internal;

import com.unifiedsupportinbox.integration.*;
import com.unifiedsupportinbox.provider.internal.ConfiguredProviderSecretResolver;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class SlackConnectionDiscovery implements ProviderChannelDiscovery,ProviderConnectionRuntime {
    private final SlackChannelDiscoveryService discovery;
    private final ProviderIntegrationCredentialLookup integrations;
    private final ConfiguredProviderSecretResolver secrets;
    private final String callback,defaultRef;
    SlackConnectionDiscovery(SlackChannelDiscoveryService discovery,ProviderIntegrationCredentialLookup integrations,
            ConfiguredProviderSecretResolver secrets,@Value("${usi.provider-callbacks.slack:}") String callback,
            @Value("${usi.providers.slack.default-secret-ref:slack/default}") String defaultRef) {
        this.discovery=discovery;this.integrations=integrations;this.secrets=secrets;this.callback=callback;this.defaultRef=defaultRef;
    }
    public IntegrationProvider provider(){return IntegrationProvider.SLACK;}
    public int discover(ProviderConnectionConfiguration.Claim claim){return discovery.discover(claim.integrationId(),claim).size();}
    public Runtime runtime(UUID integration) {
        String ref=integrations.findForProvider(provider()).stream().filter(i->i.integrationId().equals(integration))
                .findFirst().map(ProviderIntegrationCredentialLookup.CredentialReference::secretRef).orElse(defaultRef);
        boolean configured=present(ref,"slack-bot-token")&&present(ref,"slack-signing-secret");
        return new Runtime(callback,"","",ref,configured);
    }
    private boolean present(String ref,String key) {
        var value=secrets.resolve(ref,key);value.ifPresent(v->Arrays.fill(v,(byte)0));return value.isPresent();
    }
}
