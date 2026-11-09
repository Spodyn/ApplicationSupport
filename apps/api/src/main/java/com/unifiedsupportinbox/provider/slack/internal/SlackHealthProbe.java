package com.unifiedsupportinbox.provider.slack.internal;

import com.unifiedsupportinbox.integration.IntegrationConnectionTester;
import com.unifiedsupportinbox.integration.IntegrationHealthReporter;
import com.unifiedsupportinbox.integration.IntegrationHealthReporter.FailureKind;
import com.unifiedsupportinbox.integration.IntegrationProvider;
import com.unifiedsupportinbox.integration.ProviderIntegrationCredentialLookup;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Performs the frozen lightweight Slack auth health check independently of event traffic. */
@Component
class SlackHealthProbe {

    private final ProviderIntegrationCredentialLookup integrations;
    private final SlackConnectionTester tester;
    private final IntegrationHealthReporter health;

    SlackHealthProbe(
            ProviderIntegrationCredentialLookup integrations,
            SlackConnectionTester tester,
            IntegrationHealthReporter health) {
        this.integrations = integrations;
        this.tester = tester;
        this.health = health;
    }

    @Scheduled(fixedDelayString = "${usi.providers.slack.auth-health-check-ms:900000}")
    void probe() {
        for (ProviderIntegrationCredentialLookup.CredentialReference integration
                : integrations.findForProvider(IntegrationProvider.SLACK)) {
            IntegrationConnectionTester.Result result = tester.test(new IntegrationConnectionTester.Request(
                    integration.integrationId(),
                    IntegrationProvider.SLACK,
                    integration.workspaceExternalId(),
                    true));
            if (result.outcome() == IntegrationConnectionTester.Outcome.SUCCESS) {
                health.providerRecovered(integration.integrationId());
            } else {
                FailureKind kind = result.outcome() == IntegrationConnectionTester.Outcome.UNAUTHORIZED
                        ? FailureKind.DISCONNECTED
                        : FailureKind.DEGRADED;
                health.providerFailure(integration.integrationId(), result.errorCode(), kind);
            }
        }
    }
}
