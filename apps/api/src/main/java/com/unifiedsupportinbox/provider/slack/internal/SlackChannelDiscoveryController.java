package com.unifiedsupportinbox.provider.slack.internal;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.channel.ChannelView;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/integrations/{integrationId}/slack")
class SlackChannelDiscoveryController {
    private final SlackChannelDiscoveryService discovery;
    SlackChannelDiscoveryController(SlackChannelDiscoveryService discovery) { this.discovery = discovery; }

    @PostMapping("/discover-channels")
    List<ChannelView> discover(@PathVariable UUID integrationId, Authentication actor) {
        if (actor == null || !actor.isAuthenticated() || actor.getAuthorities().stream()
                .noneMatch(authority -> "manage_integrations".equals(authority.getAuthority()))) {
            throw ApiProblemException.accessDenied();
        }
        return discovery.discover(integrationId);
    }
}
