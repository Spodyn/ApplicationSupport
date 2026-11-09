package com.unifiedsupportinbox.provider.slack.internal;

import com.unifiedsupportinbox.integration.IntegrationProvider;
import com.unifiedsupportinbox.integration.ProviderIntegrationCredentialLookup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Reconcile app membership outside database transactions; no message history is fetched. */
@Component
@ConditionalOnProperty(name = "usi.slack.channel-discovery.enabled", havingValue = "true")
class SlackChannelDiscoveryScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(SlackChannelDiscoveryScheduler.class);
    private final ProviderIntegrationCredentialLookup integrations;
    private final SlackChannelDiscoveryService discovery;

    SlackChannelDiscoveryScheduler(ProviderIntegrationCredentialLookup integrations, SlackChannelDiscoveryService discovery) {
        this.integrations = integrations;
        this.discovery = discovery;
    }

    @Scheduled(fixedDelayString = "${usi.slack.channel-discovery.poll-interval:30s}")
    void reconcile() {
        for (var integration : integrations.findEnabledForProvider(IntegrationProvider.SLACK)) {
            try {
                discovery.discover(integration.integrationId());
            } catch (RuntimeException failure) {
                // Provider responses/exception messages can contain sensitive material.
                LOG.warn("Slack channel reconciliation failed; integrationId={}", integration.integrationId());
            }
        }
    }
}
