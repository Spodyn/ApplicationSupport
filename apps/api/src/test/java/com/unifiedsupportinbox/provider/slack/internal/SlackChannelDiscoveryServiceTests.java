package com.unifiedsupportinbox.provider.slack.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.unifiedsupportinbox.channel.ChannelDiscoveryCatalog;
import com.unifiedsupportinbox.channel.DiscoveredChannel;
import com.unifiedsupportinbox.integration.IntegrationProvider;
import com.unifiedsupportinbox.integration.ProviderIntegrationCredentialLookup;
import com.unifiedsupportinbox.integration.ProviderIntegrationCredentialLookup.CredentialReference;
import com.unifiedsupportinbox.provider.internal.ConfiguredProviderSecretResolver;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class SlackChannelDiscoveryServiceTests {
    final UUID id = UUID.randomUUID();
    final ProviderIntegrationCredentialLookup integrations = mock(ProviderIntegrationCredentialLookup.class);
    final ConfiguredProviderSecretResolver secrets = mock(ConfiguredProviderSecretResolver.class);
    final SlackAdministrationApiClient slack = mock(SlackAdministrationApiClient.class);
    final ChannelDiscoveryCatalog channels = mock(ChannelDiscoveryCatalog.class);
    final SlackChannelDiscoveryService service = new SlackChannelDiscoveryService(integrations, secrets, slack, channels, new ObjectMapper());

    void credentials() {
        when(integrations.findForProvider(IntegrationProvider.SLACK)).thenReturn(List.of(new CredentialReference(id, "T-test", "slack/test")));
        when(secrets.resolve(anyString(), anyString())).thenReturn(Optional.of("fake-test-token".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        when(slack.testAuthentication(any())).thenReturn(new SlackAdministrationApiClient.AuthResponse(200, true, "T-test", "Test", null));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void onlyBotMembershipIsRegisteredAndSnapshotWaitsForEveryPage() {
        credentials();
        when(slack.listConversations(any(), eq("T-test"), isNull())).thenReturn(new SlackAdministrationApiClient.ConversationsResponse(200, true,
                List.of(new SlackAdministrationApiClient.Conversation("C-member", "member", true, false, false),
                        new SlackAdministrationApiClient.Conversation("C-unrelated", "unrelated", false, false, false)), "next", null));
        when(slack.listConversations(any(), eq("T-test"), eq("next"))).thenReturn(new SlackAdministrationApiClient.ConversationsResponse(200, true,
                List.of(new SlackAdministrationApiClient.Conversation("G-private", "private", true, true, true)), null, null));
        service.discover(id);
        ArgumentCaptor<List<DiscoveredChannel>> snapshot = ArgumentCaptor.forClass((Class) List.class);
        verify(channels).replace(eq(id), snapshot.capture());
        assertThat(snapshot.getValue()).extracting(DiscoveredChannel::externalChannelId).containsExactly("C-member", "G-private");
        assertThat(snapshot.getValue()).allMatch(DiscoveredChannel::active);
    }

    @Test
    void failedLaterPageDoesNotDeactivateExistingChannels() {
        credentials();
        when(slack.listConversations(any(), eq("T-test"), isNull())).thenReturn(new SlackAdministrationApiClient.ConversationsResponse(200, true, List.of(), "next", null));
        when(slack.listConversations(any(), eq("T-test"), eq("next"))).thenReturn(new SlackAdministrationApiClient.ConversationsResponse(429, false, List.of(), null, "ratelimited"));
        assertThatThrownBy(() -> service.discover(id)).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(channels);
    }

    @Test
    void credentialsForAnotherWorkspaceCannotRegisterChannels() {
        credentials();
        when(slack.testAuthentication(any())).thenReturn(new SlackAdministrationApiClient.AuthResponse(200, true, "T-other", "Other", null));
        assertThatThrownBy(() -> service.discover(id)).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(channels);
        verify(slack, never()).listConversations(any(), anyString(), any());
    }
}
