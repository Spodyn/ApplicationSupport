package com.unifiedsupportinbox.integration.internal;

import com.unifiedsupportinbox.integration.IntegrationConnectionTestView;
import com.unifiedsupportinbox.integration.IntegrationStatus;
import com.unifiedsupportinbox.integration.IntegrationView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    @PatchMapping("/{integrationId}/status")
    IntegrationView setStatus(
            @PathVariable UUID integrationId,
            @Valid @RequestBody IntegrationStatusRequest input,
            Authentication actor) {
        return integrations.setStatus(actor, integrationId, input.status());
    }

    @PostMapping("/{integrationId}/test")
    IntegrationConnectionTestView testConnection(@PathVariable UUID integrationId, Authentication actor) {
        return integrations.testConnection(actor, integrationId);
    }

    record IntegrationStatusRequest(@NotNull IntegrationStatus status) {
    }
}
