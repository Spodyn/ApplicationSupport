package com.unifiedsupportinbox.integration.internal;

import com.unifiedsupportinbox.integration.IntegrationConnectionTestView;
import com.unifiedsupportinbox.integration.IntegrationView;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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

    @PostMapping
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    IntegrationView create(
            @Valid @RequestBody IntegrationConfigurationRequest input,
            Authentication actor) {
        return integrations.create(actor, input.toInput());
    }

    @PutMapping("/{integrationId}")
    IntegrationView update(
            @PathVariable UUID integrationId,
            @Valid @RequestBody IntegrationConfigurationRequest input,
            Authentication actor) {
        return integrations.update(actor, integrationId, input.toInput());
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

    record IntegrationConfigurationRequest(
            @NotNull com.unifiedsupportinbox.integration.IntegrationProvider provider,
            @NotBlank @Size(max = 160) String displayName,
            @NotBlank @Size(max = 255) String workspaceExternalId,
            @NotBlank @Size(max = 255) String workspaceName,
            @NotBlank @Size(max = 512) String secretRef) {
        IntegrationService.IntegrationConfigurationInput toInput() {
            return new IntegrationService.IntegrationConfigurationInput(
                    provider, displayName, workspaceExternalId, workspaceName, secretRef);
        }
    }

    record IntegrationStatusRequest(
            @NotNull com.unifiedsupportinbox.integration.IntegrationStatus status) {
    }
}
