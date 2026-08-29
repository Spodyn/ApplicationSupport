package com.unifiedsupportinbox.notification.internal;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.integration.IntegrationProvider;
import com.unifiedsupportinbox.notification.NotificationDestinationView;
import com.unifiedsupportinbox.notification.NotificationRouteView;
import com.unifiedsupportinbox.notification.NotificationRoutingCatalog;
import com.unifiedsupportinbox.notification.NotificationRuleView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class NotificationService implements NotificationRoutingCatalog {

    private static final String MANAGE_NOTIFICATIONS = "manage_notifications";
    private static final Pattern FILTER_IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_.:-]{0,63}");
    private static final int MAX_FILTERS = 64;

    private final NotificationRepository notifications;

    NotificationService(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    @Transactional(readOnly = true)
    List<NotificationDestinationView> listDestinations(Authentication actor) {
        requireManageNotifications(actor);
        return notifications.findDestinations().stream().map(DestinationRecord::toView).toList();
    }

    @Transactional
    NotificationDestinationView createDestination(Authentication actor, DestinationInput input) {
        requireManageNotifications(actor);
        NormalizedDestination normalized = normalizeDestination(input);
        validateIntegration(normalized.integrationId(), normalized.provider());
        try {
            DestinationRecord created = notifications.createDestination(
                    normalized.name(), normalized.provider(), normalized.integrationId(), normalized.targetRef(),
                    normalized.enabled(), normalized.secretRef(), normalized.configRef());
            auditDestination(created, "CREATED", actor.getName());
            return created.toView();
        } catch (DataIntegrityViolationException exception) {
            throw ApiProblemException.conflict("A notification destination already uses this provider target.");
        }
    }

    @Transactional
    NotificationDestinationView updateDestination(
            Authentication actor,
            UUID id,
            long expectedVersion,
            DestinationInput input) {
        requireManageNotifications(actor);
        requireVersion(expectedVersion);
        DestinationRecord current = notifications.findDestination(id)
                .orElseThrow(() -> ApiProblemException.notFound("Notification destination was not found."));
        NormalizedDestination normalized = normalizeDestination(input);
        validateIntegration(normalized.integrationId(), normalized.provider());
        String secretRef = normalized.secretRef() == null ? current.secretRef() : normalized.secretRef();
        String configRef = normalized.configRef() == null ? current.configRef() : normalized.configRef();
        try {
            DestinationRecord updated = notifications.updateDestination(
                            id, expectedVersion, normalized.name(), normalized.provider(), normalized.integrationId(),
                            normalized.targetRef(), normalized.enabled(), secretRef, configRef)
                    .orElseThrow(() -> ApiProblemException.conflict(
                            "Notification destination changed since it was loaded."));
            auditDestination(updated, "UPDATED", actor.getName());
            return updated.toView();
        } catch (DataIntegrityViolationException exception) {
            throw ApiProblemException.conflict("A notification destination already uses this provider target.");
        }
    }

    @Transactional
    NotificationDestinationView setDestinationEnabled(
            Authentication actor,
            UUID id,
            long expectedVersion,
            boolean enabled) {
        requireManageNotifications(actor);
        DestinationRecord current = notifications.findDestination(id)
                .orElseThrow(() -> ApiProblemException.notFound("Notification destination was not found."));
        return updateDestination(actor, id, expectedVersion, new DestinationInput(
                current.name(), current.provider(), current.integrationId(), current.targetRef(), enabled,
                null, null));
    }

    @Transactional
    void deleteDestination(Authentication actor, UUID id, long expectedVersion) {
        requireManageNotifications(actor);
        requireVersion(expectedVersion);
        DestinationRecord current = notifications.findDestination(id)
                .orElseThrow(() -> ApiProblemException.notFound("Notification destination was not found."));
        if (!notifications.deleteDestination(id, expectedVersion)) {
            throw ApiProblemException.conflict("Notification destination changed since it was loaded.");
        }
        auditDestination(current, "DELETED", actor.getName());
    }

    @Transactional(readOnly = true)
    List<NotificationRuleView> listRules(Authentication actor) {
        requireManageNotifications(actor);
        return notifications.findRules().stream().map(RuleRecord::toView).toList();
    }

    @Transactional
    NotificationRuleView createRule(Authentication actor, RuleInput input) {
        requireManageNotifications(actor);
        NormalizedRule normalized = normalizeRule(input);
        requireDestination(normalized.destinationId());
        try {
            RuleRecord created = notifications.createRule(
                    normalized.destinationId(), normalized.name(), normalized.enabled(),
                    normalized.eventTypes(), normalized.severityFilters());
            auditRule(created, "CREATED", actor.getName());
            return created.toView();
        } catch (DataIntegrityViolationException exception) {
            throw ApiProblemException.conflict("A notification rule with this name already exists for the destination.");
        }
    }

    @Transactional
    NotificationRuleView updateRule(Authentication actor, UUID id, long expectedVersion, RuleInput input) {
        requireManageNotifications(actor);
        requireVersion(expectedVersion);
        if (notifications.findRule(id).isEmpty()) {
            throw ApiProblemException.notFound("Notification rule was not found.");
        }
        NormalizedRule normalized = normalizeRule(input);
        requireDestination(normalized.destinationId());
        try {
            RuleRecord updated = notifications.updateRule(
                            id, expectedVersion, normalized.destinationId(), normalized.name(), normalized.enabled(),
                            normalized.eventTypes(), normalized.severityFilters())
                    .orElseThrow(() -> ApiProblemException.conflict(
                            "Notification rule changed since it was loaded."));
            auditRule(updated, "UPDATED", actor.getName());
            return updated.toView();
        } catch (DataIntegrityViolationException exception) {
            throw ApiProblemException.conflict("A notification rule with this name already exists for the destination.");
        }
    }

    @Transactional
    void deleteRule(Authentication actor, UUID id, long expectedVersion) {
        requireManageNotifications(actor);
        requireVersion(expectedVersion);
        RuleRecord current = notifications.findRule(id)
                .orElseThrow(() -> ApiProblemException.notFound("Notification rule was not found."));
        if (!notifications.deleteRule(id, expectedVersion)) {
            throw ApiProblemException.conflict("Notification rule changed since it was loaded.");
        }
        auditRule(current, "DELETED", actor.getName());
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationRouteView> listEnabledRoutes() {
        return notifications.findEnabledRoutes();
    }

    private void validateIntegration(UUID integrationId, IntegrationProvider provider) {
        IntegrationProvider actual = notifications.findIntegrationProvider(integrationId)
                .orElseThrow(() -> ApiProblemException.validationFailed("Notification integration was not found."));
        if (actual != provider) {
            throw ApiProblemException.validationFailed("Notification provider must match the selected integration.");
        }
    }

    private DestinationRecord requireDestination(UUID destinationId) {
        return notifications.findDestination(destinationId)
                .orElseThrow(() -> ApiProblemException.validationFailed("Notification destination was not found."));
    }

    private static NormalizedDestination normalizeDestination(DestinationInput input) {
        if (input == null || input.provider() == null || input.integrationId() == null) {
            throw ApiProblemException.validationFailed("Notification provider and integration are required.");
        }
        return new NormalizedDestination(
                requiredText(input.name(), "Notification destination name", 160),
                input.provider(),
                input.integrationId(),
                requiredText(input.targetRef(), "Notification target reference", 512),
                input.enabled(),
                optionalText(input.secretRef(), "Notification secret reference", 512),
                optionalText(input.configRef(), "Notification configuration reference", 512));
    }

    private static NormalizedRule normalizeRule(RuleInput input) {
        if (input == null || input.destinationId() == null) {
            throw ApiProblemException.validationFailed("Notification rule destination is required.");
        }
        return new NormalizedRule(
                input.destinationId(),
                requiredText(input.name(), "Notification rule name", 160),
                input.enabled(),
                normalizeFilters(input.eventTypes(), "Notification event types", true),
                normalizeFilters(input.severityFilters(), "Notification severity filters", false));
    }

    private static List<String> normalizeFilters(List<String> values, String field, boolean required) {
        if (values == null) {
            throw ApiProblemException.validationFailed(field + " are required.");
        }
        if (required && values.isEmpty()) {
            throw ApiProblemException.validationFailed(field + " must contain at least one value.");
        }
        if (values.size() > MAX_FILTERS) {
            throw ApiProblemException.validationFailed(field + " contain too many values.");
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || !FILTER_IDENTIFIER.matcher(value).matches()) {
                throw ApiProblemException.validationFailed(field + " contain an invalid identifier.");
            }
            unique.add(value);
        }
        return List.copyOf(unique);
    }

    private static String requiredText(String value, String field, int maxLength) {
        String normalized = optionalText(value, field, maxLength);
        if (normalized == null) throw ApiProblemException.validationFailed(field + " is required.");
        return normalized;
    }

    private static String optionalText(String value, String field, int maxLength) {
        if (value == null) return null;
        String normalized = value.strip();
        if (normalized.isEmpty()) return null;
        if (normalized.length() > maxLength || normalized.indexOf('\n') >= 0 || normalized.indexOf('\r') >= 0) {
            throw ApiProblemException.validationFailed(field + " is invalid.");
        }
        return normalized;
    }

    private static void requireVersion(long version) {
        if (version < 1) throw ApiProblemException.validationFailed("Notification configuration version must be positive.");
    }

    private static void requireManageNotifications(Authentication actor) {
        boolean allowed = actor != null
                && actor.isAuthenticated()
                && actor.getAuthorities().stream()
                        .anyMatch(authority -> MANAGE_NOTIFICATIONS.equals(authority.getAuthority()));
        if (!allowed) throw ApiProblemException.accessDenied();
    }

    private void auditDestination(DestinationRecord record, String action, String actor) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", record.name());
        snapshot.put("provider", record.provider().name());
        snapshot.put("integrationId", record.integrationId().toString());
        snapshot.put("targetRef", record.targetRef());
        snapshot.put("enabled", record.enabled());
        snapshot.put("secretConfigured", record.secretRef() != null);
        snapshot.put("configConfigured", record.configRef() != null);
        notifications.appendChange("DESTINATION", record.id(), action, actor, record.version(), snapshot);
    }

    private void auditRule(RuleRecord record, String action, String actor) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("destinationId", record.destinationId().toString());
        snapshot.put("name", record.name());
        snapshot.put("enabled", record.enabled());
        snapshot.put("eventTypes", new ArrayList<>(record.eventTypes()));
        snapshot.put("severityFilters", new ArrayList<>(record.severityFilters()));
        notifications.appendChange("RULE", record.id(), action, actor, record.version(), snapshot);
    }

    record DestinationInput(
            String name,
            IntegrationProvider provider,
            UUID integrationId,
            String targetRef,
            boolean enabled,
            String secretRef,
            String configRef) {
    }

    record RuleInput(
            UUID destinationId,
            String name,
            boolean enabled,
            List<String> eventTypes,
            List<String> severityFilters) {
    }

    private record NormalizedDestination(
            String name,
            IntegrationProvider provider,
            UUID integrationId,
            String targetRef,
            boolean enabled,
            String secretRef,
            String configRef) {
    }

    private record NormalizedRule(
            UUID destinationId,
            String name,
            boolean enabled,
            List<String> eventTypes,
            List<String> severityFilters) {
    }
}
