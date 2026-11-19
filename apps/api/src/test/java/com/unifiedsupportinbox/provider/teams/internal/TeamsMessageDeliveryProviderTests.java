package com.unifiedsupportinbox.provider.teams.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.unifiedsupportinbox.integration.*;
import com.unifiedsupportinbox.messaging.*;
import com.unifiedsupportinbox.provider.internal.ConfiguredProviderSecretResolver;
import java.util.*;
import org.junit.jupiter.api.Test;

class TeamsMessageDeliveryProviderTests {
    final ProviderIntegrationCredentialLookup integrations=mock(ProviderIntegrationCredentialLookup.class);
    final ConfiguredProviderSecretResolver secrets=mock(ConfiguredProviderSecretResolver.class);
    final TeamsRouteStore routes=mock(TeamsRouteStore.class);
    final TeamsConnectorClient connector=mock(TeamsConnectorClient.class);
    final UUID integration=UUID.randomUUID();
    final String client="11111111-1111-4111-8111-111111111111",tenant="22222222-2222-4222-8222-222222222222";
    final TeamsOutboundAttachments files=mock(TeamsOutboundAttachments.class);
    final TeamsOutboundAcceptance acceptance=mock(TeamsOutboundAcceptance.class);
    final TeamsMessageDeliveryProvider provider=new TeamsMessageDeliveryProvider(integrations,secrets,routes,connector,client,tenant,files,acceptance);
    MessageDeliveryProvider.DeliveryCommand command(String accepted) {
        return new MessageDeliveryProvider.DeliveryCommand(UUID.randomUUID(),"key",UUID.randomUUID(),IntegrationProvider.TEAMS,
                integration,"channel","root","reply",MessageBodyFormat.PLAIN_TEXT,"correlation",accepted,ref->{});
    }
    @Test void recordedProviderAcceptanceIsNeverSentAgain() {
        assertThat(provider.deliver(command("already-sent")).providerMessageRef()).isEqualTo("already-sent");
        verifyNoInteractions(connector,secrets,routes,integrations);
    }
    @Test void durableAsynchronousAcceptanceSkipsFilesAndSendingWithoutInventingAnId() {
        var command=command(null);when(acceptance.accepted(command)).thenReturn(true);
        assertThat(provider.deliver(command)).isEqualTo(MessageDeliveryProvider.DeliveryResult.sent(null));
        verifyNoInteractions(files,connector,secrets,routes,integrations);
    }
    @Test void recordedAcceptanceOfAReplyWithFilesStillCompletesWithoutAnotherProviderCall() {
        when(files.prepare(any())).thenThrow(new AssertionError("Accepted reply must not prepare its files again."));
        assertThat(provider.deliver(command("accepted-message-with-files")))
                .isEqualTo(MessageDeliveryProvider.DeliveryResult.sent("accepted-message-with-files"));
        verifyNoInteractions(connector,secrets,routes,integrations,files);
    }
    @Test void missingOrOtherTenantCredentialCannotSend() {
        when(integrations.findEnabledForProvider(IntegrationProvider.TEAMS)).thenReturn(List.of(
                new ProviderIntegrationCredentialLookup.CredentialReference(integration,"other-tenant","synthetic/teams")));
        assertThat(provider.deliver(command(null)).errorCode()).isEqualTo("TEAMS_CREDENTIAL_REFERENCE_MISSING");
        verifyNoInteractions(connector,secrets,routes);
    }
    @Test void missingOrUnapprovedRouteCannotSendToAnotherThread() {
        when(integrations.findEnabledForProvider(IntegrationProvider.TEAMS)).thenReturn(List.of(
                new ProviderIntegrationCredentialLookup.CredentialReference(integration,tenant,"synthetic/teams")));
        assertThat(provider.deliver(command(null)).errorCode()).isEqualTo("TEAMS_CONVERSATION_ROUTE_MISSING");
        verifyNoInteractions(connector,secrets);
    }
    @Test void correctRouteResolvesOnlyTheSecretReferenceAndWipesTransientCredentialBytes() {
        var c=command(null);
        var route=new TeamsRouteStore.Route("conversation","https://smba.trafficmanager.net/emea/","28:"+client,"standardChannel");
        byte[] credential="test-only-teams-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(integrations.findEnabledForProvider(IntegrationProvider.TEAMS)).thenReturn(List.of(
                new ProviderIntegrationCredentialLookup.CredentialReference(integration,tenant,"synthetic/teams")));
        when(routes.find(integration,"channel","root")).thenReturn(route);
        when(routes.approvedContext(integration,"channel","standardChannel")).thenReturn(true);
        when(secrets.resolve("synthetic/teams","teams-client-secret")).thenReturn(Optional.of(credential));
        when(connector.token(client,tenant,credential)).thenReturn("synthetic-token");
        when(connector.send("synthetic-token",route,c)).thenReturn(MessageDeliveryProvider.DeliveryResult.sent("reply-id"));
        assertThat(provider.deliver(c).providerMessageRef()).isEqualTo("reply-id");
        assertThat(credential).containsOnly((byte)0);
    }
}
