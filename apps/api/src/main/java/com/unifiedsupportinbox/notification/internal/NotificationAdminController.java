package com.unifiedsupportinbox.notification.internal;

import com.unifiedsupportinbox.integration.IntegrationProvider;
import com.unifiedsupportinbox.notification.NotificationDestinationView;
import com.unifiedsupportinbox.notification.NotificationRuleView;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/notifications")
class NotificationAdminController {

    private final NotificationService notifications;

    NotificationAdminController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping("/destinations")
    List<NotificationDestinationView> listDestinations(Authentication actor) {
        return notifications.listDestinations(actor);
    }

    @PostMapping("/destinations")
    @ResponseStatus(HttpStatus.CREATED)
    NotificationDestinationView createDestination(
            @RequestBody DestinationRequest input,
            Authentication actor) {
        return notifications.createDestination(actor, destinationInput(input));
    }

    @PutMapping("/destinations/{id}")
    NotificationDestinationView updateDestination(
            @PathVariable UUID id,
            @RequestBody VersionedDestinationRequest input,
            Authentication actor) {
        return notifications.updateDestination(
                actor,
                id,
                input == null || input.version() == null ? 0 : input.version(),
                input == null ? null : destinationInput(input.destination()));
    }

    @PatchMapping("/destinations/{id}/enabled")
    NotificationDestinationView setDestinationEnabled(
            @PathVariable UUID id,
            @RequestBody EnabledRequest input,
            Authentication actor) {
        if (input == null || input.version() == null || input.enabled() == null) {
            return notifications.setDestinationEnabled(actor, id, 0, false);
        }
        return notifications.setDestinationEnabled(actor, id, input.version(), input.enabled());
    }

    @DeleteMapping("/destinations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteDestination(
            @PathVariable UUID id,
            @RequestParam long version,
            Authentication actor) {
        notifications.deleteDestination(actor, id, version);
    }

    @GetMapping("/rules")
    List<NotificationRuleView> listRules(Authentication actor) {
        return notifications.listRules(actor);
    }

    @PostMapping("/rules")
    @ResponseStatus(HttpStatus.CREATED)
    NotificationRuleView createRule(@RequestBody RuleRequest input, Authentication actor) {
        return notifications.createRule(actor, ruleInput(input));
    }

    @PutMapping("/rules/{id}")
    NotificationRuleView updateRule(
            @PathVariable UUID id,
            @RequestBody VersionedRuleRequest input,
            Authentication actor) {
        return notifications.updateRule(
                actor,
                id,
                input == null || input.version() == null ? 0 : input.version(),
                input == null ? null : ruleInput(input.rule()));
    }

    @DeleteMapping("/rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteRule(
            @PathVariable UUID id,
            @RequestParam long version,
            Authentication actor) {
        notifications.deleteRule(actor, id, version);
    }

    private static NotificationService.DestinationInput destinationInput(DestinationRequest input) {
        return input == null ? null : new NotificationService.DestinationInput(
                input.name(), input.provider(), input.integrationId(), input.targetRef(), input.enabled(),
                input.secretRef(), input.configRef());
    }

    private static NotificationService.RuleInput ruleInput(RuleRequest input) {
        return input == null ? null : new NotificationService.RuleInput(
                input.destinationId(), input.name(), input.enabled(), input.eventTypes(), input.severityFilters());
    }

    record DestinationRequest(
            String name,
            IntegrationProvider provider,
            UUID integrationId,
            String targetRef,
            boolean enabled,
            String secretRef,
            String configRef) {
    }

    record VersionedDestinationRequest(Long version, DestinationRequest destination) {
    }

    record EnabledRequest(Long version, Boolean enabled) {
    }

    record RuleRequest(
            UUID destinationId,
            String name,
            boolean enabled,
            List<String> eventTypes,
            List<String> severityFilters) {
    }

    record VersionedRuleRequest(Long version, RuleRequest rule) {
    }
}
