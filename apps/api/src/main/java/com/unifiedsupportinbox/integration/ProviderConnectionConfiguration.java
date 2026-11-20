package com.unifiedsupportinbox.integration;

import java.time.*;
import java.util.*;

/** Persisted discovery controls and the single selected Teams security boundary. */
public interface ProviderConnectionConfiguration {
    record Settings(UUID integrationId, boolean automaticDiscovery, String discoveryState,
            Instant lastDiscoveredAt, Instant nextDiscoveryAt, String errorCode, int discoveredChannels) { }
    record TeamScope(UUID integrationId, UUID teamId, String botTeamId, UUID defaultCustomerId) { }
    record Claim(UUID integrationId, IntegrationProvider provider, UUID lease) { }
    Settings settings(UUID integrationId);
    Settings configure(UUID integrationId, boolean automaticDiscovery);
    Optional<TeamScope> teamScope(UUID integrationId);
    void selectTeam(UUID integrationId, UUID teamId, UUID defaultCustomerId);
    void adoptConfiguredTeamsTenant(UUID integrationId, String tenantId);
    void verifyBotTeam(UUID integrationId, UUID teamId, String botTeamId);
    void lockTeam(UUID integrationId, UUID teamId);
    void requestDiscovery(UUID integrationId);
    Optional<Claim> claimDue();
    boolean renew(Claim claim);
    void lockDiscovery(Claim claim, UUID teamId);
    void finished(Claim claim, String state, String errorCode, int count, Duration retryAfter);
}
