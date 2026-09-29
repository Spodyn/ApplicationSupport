package com.unifiedsupportinbox.integration.internal;

import com.unifiedsupportinbox.integration.IntegrationDisconnected;
import com.unifiedsupportinbox.integration.IntegrationHealth;
import com.unifiedsupportinbox.integration.IntegrationHealthReporter;
import com.unifiedsupportinbox.integration.IntegrationProvider;
import com.unifiedsupportinbox.integration.IntegrationStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class IntegrationHealthCoordinator implements IntegrationHealthReporter {

    static final String EVENT_SILENCE_ERROR = "SLACK_EVENT_SILENCE";

    private final IntegrationRepository integrations;
    private final ApplicationEventPublisher events;
    private final Duration eventSilenceThreshold;

    IntegrationHealthCoordinator(
            IntegrationRepository integrations,
            ApplicationEventPublisher events,
            @Value("${usi.providers.slack.event-silence-ms:3600000}") long eventSilenceMs) {
        if (eventSilenceMs < 60_000) {
            throw new IllegalArgumentException("Slack event silence threshold must be at least 60000 ms.");
        }
        this.integrations = integrations;
        this.events = events;
        this.eventSilenceThreshold = Duration.ofMillis(eventSilenceMs);
    }

    @Override
    @Transactional
    public void providerEventReceived(UUID integrationId, Instant receivedAt) {
        if (integrationId == null || receivedAt == null) return;
        IntegrationRecord current = integrations.findById(integrationId).orElse(null);
        if (current == null || current.status() != IntegrationStatus.ENABLED) return;

        Instant latest = current.lastEventAt() == null || receivedAt.isAfter(current.lastEventAt())
                ? receivedAt
                : current.lastEventAt();
        if (current.health() == IntegrationHealth.UNAVAILABLE) {
            integrations.updateHealth(integrationId, IntegrationHealth.UNAVAILABLE, latest, current.lastErrorCode());
            return;
        }
        integrations.updateHealth(integrationId, IntegrationHealth.HEALTHY, latest, null);
    }

    @Override
    @Transactional
    public void providerFailure(UUID integrationId, String errorCode, FailureKind kind) {
        if (integrationId == null || kind == null) return;
        IntegrationRecord current = integrations.findById(integrationId).orElse(null);
        if (current == null || current.status() != IntegrationStatus.ENABLED) return;

        String code = normalizeErrorCode(errorCode);
        IntegrationHealth target = kind == FailureKind.DISCONNECTED
                ? IntegrationHealth.UNAVAILABLE
                : IntegrationHealth.DEGRADED;
        boolean publishDisconnect = kind == FailureKind.DISCONNECTED
                && (current.health() != IntegrationHealth.UNAVAILABLE
                    || !Objects.equals(current.lastErrorCode(), code));

        integrations.updateHealth(integrationId, target, current.lastEventAt(), code);
        if (publishDisconnect) {
            events.publishEvent(new IntegrationDisconnected(
                    current.id(), current.provider(), code, Instant.now()));
        }
    }

    @Override
    @Transactional
    public void providerRecovered(UUID integrationId) {
        if (integrationId == null) return;
        IntegrationRecord current = integrations.findById(integrationId).orElse(null);
        if (current == null || current.status() != IntegrationStatus.ENABLED) return;
        if (current.health() == IntegrationHealth.HEALTHY && current.lastErrorCode() == null) return;
        integrations.updateHealth(integrationId, IntegrationHealth.HEALTHY, current.lastEventAt(), null);
    }

    @Scheduled(fixedDelayString = "${usi.providers.slack.health-check-ms:60000}")
    @Transactional
    public void detectEventSilence() {
        Instant cutoff = Instant.now().minus(eventSilenceThreshold);
        for (IntegrationRecord current : integrations.findAll()) {
            if (current.provider() != IntegrationProvider.SLACK
                    || current.status() != IntegrationStatus.ENABLED
                    || current.health() == IntegrationHealth.UNAVAILABLE) {
                continue;
            }
            Instant activity = current.lastEventAt() == null ? current.createdAt() : current.lastEventAt();
            if (activity == null || !activity.isBefore(cutoff)) continue;
            if (current.health() == IntegrationHealth.DEGRADED
                    && EVENT_SILENCE_ERROR.equals(current.lastErrorCode())) {
                continue;
            }
            integrations.updateHealth(
                    current.id(), IntegrationHealth.DEGRADED, current.lastEventAt(), EVENT_SILENCE_ERROR);
        }
    }

    private static String normalizeErrorCode(String value) {
        if (value == null || value.isBlank()) return "PROVIDER_UNAVAILABLE";
        String normalized = value.strip().replaceAll("[^A-Za-z0-9_.:-]+", "_");
        return normalized.length() <= 128 ? normalized : normalized.substring(0, 128);
    }
}
