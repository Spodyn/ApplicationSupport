package com.unifiedsupportinbox.integration.internal;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.unifiedsupportinbox.integration.IntegrationDisconnected;
import com.unifiedsupportinbox.integration.IntegrationHealth;
import com.unifiedsupportinbox.integration.IntegrationHealthReporter.FailureKind;
import com.unifiedsupportinbox.integration.IntegrationProvider;
import com.unifiedsupportinbox.integration.IntegrationStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

class IntegrationHealthCoordinatorTests {

    private IntegrationRepository integrations;
    private ApplicationEventPublisher events;
    private IntegrationHealthCoordinator coordinator;

    @BeforeEach
    void setUp() {
        integrations = mock(IntegrationRepository.class);
        events = mock(ApplicationEventPublisher.class);
        coordinator = new IntegrationHealthCoordinator(integrations, events, 60_000);
    }

    @Test
    void authenticatedProviderEventMarksIntegrationHealthyAndAdvancesLastEvent() {
        UUID id = UUID.randomUUID();
        Instant previous = Instant.now().minusSeconds(30);
        Instant received = Instant.now();
        when(integrations.findById(id)).thenReturn(Optional.of(record(
                id, IntegrationProvider.SLACK, IntegrationHealth.DEGRADED, previous, "SLACK_EVENT_SILENCE",
                Instant.now().minusSeconds(120))));

        coordinator.providerEventReceived(id, received);

        verify(integrations).updateHealth(id, IntegrationHealth.HEALTHY, received, null);
    }

    @Test
    void revokedAuthorizationMarksUnavailableAndPublishesDisconnectTransition() {
        UUID id = UUID.randomUUID();
        IntegrationRecord current = record(
                id, IntegrationProvider.SLACK, IntegrationHealth.HEALTHY, Instant.now(), null,
                Instant.now().minusSeconds(120));
        when(integrations.findById(id)).thenReturn(Optional.of(current));

        coordinator.providerFailure(id, "SLACK_TOKEN_REVOKED", FailureKind.DISCONNECTED);

        verify(integrations).updateHealth(
                id, IntegrationHealth.UNAVAILABLE, current.lastEventAt(), "SLACK_TOKEN_REVOKED");
        ArgumentCaptor<IntegrationDisconnected> event = ArgumentCaptor.forClass(IntegrationDisconnected.class);
        verify(events).publishEvent(event.capture());
        org.assertj.core.api.Assertions.assertThat(event.getValue().integrationId()).isEqualTo(id);
        org.assertj.core.api.Assertions.assertThat(event.getValue().errorCode()).isEqualTo("SLACK_TOKEN_REVOKED");
    }

    @Test
    void repeatedSameDisconnectDoesNotPublishDuplicateNotificationEvent() {
        UUID id = UUID.randomUUID();
        IntegrationRecord current = record(
                id, IntegrationProvider.SLACK, IntegrationHealth.UNAVAILABLE, Instant.now(), "SLACK_TOKEN_REVOKED",
                Instant.now().minusSeconds(120));
        when(integrations.findById(id)).thenReturn(Optional.of(current));

        coordinator.providerFailure(id, "SLACK_TOKEN_REVOKED", FailureKind.DISCONNECTED);

        verify(events, never()).publishEvent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void providerRecoveryClearsFailureAndReturnsHealthy() {
        UUID id = UUID.randomUUID();
        Instant lastEvent = Instant.now().minusSeconds(15);
        when(integrations.findById(id)).thenReturn(Optional.of(record(
                id, IntegrationProvider.SLACK, IntegrationHealth.UNAVAILABLE, lastEvent, "SLACK_INVALID_AUTH",
                Instant.now().minusSeconds(120))));

        coordinator.providerRecovered(id);

        verify(integrations).updateHealth(id, IntegrationHealth.HEALTHY, lastEvent, null);
    }

    @Test
    void eventSilenceDegradesOnlyEnabledSlackIntegrationPastThreshold() {
        Instant now = Instant.now();
        UUID staleId = UUID.randomUUID();
        UUID recentId = UUID.randomUUID();
        UUID teamsId = UUID.randomUUID();
        when(integrations.findAll()).thenReturn(List.of(
                record(staleId, IntegrationProvider.SLACK, IntegrationHealth.HEALTHY,
                        now.minusSeconds(120), null, now.minusSeconds(300)),
                record(recentId, IntegrationProvider.SLACK, IntegrationHealth.HEALTHY,
                        now, null, now.minusSeconds(300)),
                record(teamsId, IntegrationProvider.TEAMS, IntegrationHealth.HEALTHY,
                        now.minusSeconds(120), null, now.minusSeconds(300))));

        coordinator.detectEventSilence();

        verify(integrations).updateHealth(
                staleId, IntegrationHealth.DEGRADED, now.minusSeconds(120),
                IntegrationHealthCoordinator.EVENT_SILENCE_ERROR);
        verify(integrations, never()).updateHealth(
                org.mockito.ArgumentMatchers.eq(recentId),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(integrations, never()).updateHealth(
                org.mockito.ArgumentMatchers.eq(teamsId),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    private static IntegrationRecord record(
            UUID id,
            IntegrationProvider provider,
            IntegrationHealth health,
            Instant lastEventAt,
            String lastErrorCode,
            Instant createdAt) {
        return new IntegrationRecord(
                id,
                provider,
                "Test integration",
                IntegrationStatus.ENABLED,
                health,
                provider == IntegrationProvider.SLACK ? "T123" : "tenant-123",
                "Workspace",
                "secret/ref",
                "{}",
                lastEventAt,
                lastErrorCode,
                createdAt,
                Instant.now());
    }
}
