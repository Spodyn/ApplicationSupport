package com.unifiedsupportinbox.integration.internal;

import com.unifiedsupportinbox.integration.IntegrationView;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/integrations")
class IntegrationAdminController {

    private final IntegrationService integrations;

    IntegrationAdminController(IntegrationService integrations) {
        this.integrations = integrations;
    }

    @GetMapping
    List<IntegrationView> list(Authentication actor) {
        return integrations.list(actor);
    }

    @GetMapping("/{integrationId}")
    IntegrationView get(@PathVariable UUID integrationId, Authentication actor) {
        return integrations.get(actor, integrationId);
    }
}
