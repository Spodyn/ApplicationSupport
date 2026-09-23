package com.unifiedsupportinbox.provider.slack.internal;

import com.unifiedsupportinbox.InboundEventOutcomeStore;
import com.unifiedsupportinbox.channel.ChannelIngestionPolicy;
import com.unifiedsupportinbox.messaging.InboundMessageCommandHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class SlackInboundNormalizationConfiguration {

    @Bean
    @ConditionalOnMissingBean(SlackInboundEventHandler.class)
    SlackInboundEventHandler slackInboundEventHandler(
            ChannelIngestionPolicy channelPolicy,
            InboundEventOutcomeStore outcomes,
            SlackNormalizer normalizer,
            ObjectProvider<InboundMessageCommandHandler> downstreamHandlers) {
        return new SlackFilteringInboundEventHandler(
                channelPolicy, outcomes, normalizer, downstreamHandlers);
    }
}
