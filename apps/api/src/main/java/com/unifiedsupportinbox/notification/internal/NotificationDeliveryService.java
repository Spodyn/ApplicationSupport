package com.unifiedsupportinbox.notification.internal;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.OutboxEventStore;
import com.unifiedsupportinbox.notification.NotificationDeliveryQueue;
import com.unifiedsupportinbox.notification.NotificationDeliveryStatus;
import com.unifiedsupportinbox.notification.NotificationDeliveryView;
import com.unifiedsupportinbox.notification.NotificationRouteView;
import com.unifiedsupportinbox.notification.NotificationRoutingCatalog;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
class NotificationDeliveryService implements NotificationDeliveryQueue {

    static final String OUTBOX_TYPE = "notification.delivery.requested";
    private static final String AGGREGATE_TYPE = "notification_delivery";
    private static final String MANAGE_NOTIFICATIONS = "manage_notifications";
    private static final String WORKER_ACTOR = "notification-worker";
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_.:-]{0,63}");
    private static final int MAX_PAYLOAD_LENGTH = 262_144;

    private final NotificationDeliveryRepository deliveries;
    private final NotificationRoutingCatalog routing;
    private final OutboxEventStore outbox;
    private final NotificationWorkerProperties properties;
    private final ObjectMapper json;

    NotificationDeliveryService(
            NotificationDeliveryRepository deliveries,
            NotificationRoutingCatalog routing,
            OutboxEventStore outbox,
            NotificationWorkerProperties properties,
            ObjectMapper json) {
        this.deliveries = deliveries;
        this.routing = routing;
        this.outbox = outbox;
        this.properties = properties;
        this.json = json;
    }

    @Override
    @Transactional
    public List<NotificationDeliveryView> enqueue(NotificationIntent input) {
        NormalizedIntent intent = normalize(input);
        List<NotificationDeliveryView> result = new ArrayList<>();
        for (NotificationRouteView route : routing.listEnabledRoutes()) {
            if (!matches(route, intent)) continue;
            String deduplicationKey = intent.intentKey() + "|" + route.ruleId();
            NotificationDeliveryRepository.InsertResult inserted = deliveries.insertIfAbsent(
                    deduplicationKey,
                    route.destinationId(),
                    route.ruleId(),
                    route.provider(),
                    route.integrationId(),
                    route.targetRef(),
                    intent.eventType(),
                    intent.severity(),
                    intent.payloadJson(),
                    intent.correlationId());
            if (inserted.created()) {
                appendHistory(inserted.delivery(), NotificationDeliveryStatus.PENDING, "ENQUEUED", null, null, WORKER_ACTOR);
                appendWakeup(inserted.delivery());
            }
            result.add(inserted.delivery().toView());
        }
        return List.copyOf(result);
    }

    @Transactional(readOnly = true)
    List<NotificationDeliveryView> listRecent(Authentication actor) {
        requireManageNotifications(actor);
        return deliveries.listRecent(200).stream().map(DeliveryRecord::toView).toList();
    }

    @Transactional
    NotificationDeliveryView replayDlq(Authentication actor, UUID id) {
        requireManageNotifications(actor);
        DeliveryRecord current = deliveries.findById(id)
                .orElseThrow(() -> ApiProblemException.notFound("Notification delivery was not found."));
        if (current.status() != NotificationDeliveryStatus.DLQ) {
            throw ApiProblemException.conflict("Only DLQ notification deliveries can be replayed manually.");
        }
        RouteState routeState = currentRouteState(current);
        if (routeState != RouteState.ENABLED) {
            throw ApiProblemException.conflict("Notification delivery cannot be replayed while its route is unavailable.");
        }
        DeliveryRecord replayed = deliveries.replayDlq(id)
                .orElseThrow(() -> ApiProblemException.conflict("Notification delivery changed before replay."));
        appendHistory(replayed, NotificationDeliveryStatus.PENDING, "MANUAL_REPLAY", null, null, actor.getName());
        appendWakeup(replayed);
        return replayed.toView();
    }

    @Transactional
    DeliveryRecord claim(UUID id) {
        DeliveryRecord claimed = deliveries.claim(id, properties.claimLease()).orElse(null);
        if (claimed != null) {
            appendHistory(claimed, NotificationDeliveryStatus.PROCESSING, "CLAIMED", null, null, WORKER_ACTOR);
        }
        return claimed;
    }

    @Transactional(readOnly = true)
    RouteState currentRouteState(DeliveryRecord delivery) {
        NotificationDeliveryRepository.CurrentRouteRecord route = deliveries
                .findCurrentRoute(delivery.destinationId(), delivery.ruleId())
                .orElse(null);
        if (route == null) return RouteState.CONFIG_MISSING;
        if (!route.destinationEnabled() || !route.ruleEnabled()) return RouteState.CONFIG_DISABLED;
        if (route.provider() != delivery.provider()
                || !route.integrationId().equals(delivery.integrationId())
                || !route.targetRef().equals(delivery.targetRef())) {
            return RouteState.CONFIG_CHANGED;
        }
        if (!route.eventTypes().contains(delivery.eventType())) return RouteState.NO_LONGER_MATCHES;
        if (!route.severityFilters().isEmpty()
                && (delivery.severity() == null || !route.severityFilters().contains(delivery.severity()))) {
            return RouteState.NO_LONGER_MATCHES;
        }
        return RouteState.ENABLED;
    }

    @Transactional
    boolean markSent(DeliveryRecord claim, String providerMessageRef) {
        boolean updated = deliveries.markSent(claim.id(), claim.attempts(), normalizeOptional(providerMessageRef, 512));
        if (updated) {
            appendHistory(claim, NotificationDeliveryStatus.SENT, "DELIVERED", null, null, WORKER_ACTOR);
        }
        return updated;
    }

    @Transactional
    boolean scheduleRetry(DeliveryRecord claim, java.time.Duration delay, String errorCode) {
        String normalizedCode = errorCode(errorCode);
        boolean updated = deliveries.scheduleRetry(
                claim.id(), claim.attempts(), delay, "TRANSIENT", normalizedCode);
        if (updated) {
            appendHistory(claim, NotificationDeliveryStatus.RETRY_SCHEDULED, "TRANSIENT_FAILURE", "TRANSIENT", normalizedCode, WORKER_ACTOR);
        }
        return updated;
    }

    @Transactional
    boolean markDlq(DeliveryRecord claim, String reason, String errorCategory, String errorCode) {
        String normalizedReason = requiredReason(reason);
        String normalizedCategory = errorCategory(errorCategory);
        String normalizedCode = errorCode(errorCode);
        boolean updated = deliveries.markDlq(
                claim.id(), claim.attempts(), normalizedReason, normalizedCategory, normalizedCode);
        if (updated) {
            appendHistory(claim, NotificationDeliveryStatus.DLQ, normalizedReason, normalizedCategory, normalizedCode, WORKER_ACTOR);
        }
        return updated;
    }

    @Transactional
    boolean cancelClaim(DeliveryRecord claim, RouteState routeState) {
        String reason = switch (routeState) {
            case CONFIG_DISABLED -> "SUPPRESSED_CONFIG_DISABLED";
            case CONFIG_MISSING -> "SUPPRESSED_CONFIG_MISSING";
            case CONFIG_CHANGED -> "SUPPRESSED_CONFIG_CHANGED";
            case NO_LONGER_MATCHES -> "SUPPRESSED_RULE_NO_LONGER_MATCHES";
            case ENABLED -> throw new IllegalArgumentException("enabled route cannot be cancelled");
        };
        boolean updated = deliveries.cancelClaim(claim.id(), claim.attempts(), reason);
        if (updated) {
            appendHistory(claim, NotificationDeliveryStatus.CANCELLED, reason, null, null, WORKER_ACTOR);
        }
        return updated;
    }

    @Transactional
    int redispatchDue() {
        List<NotificationDeliveryRepository.DueRecord> due = deliveries.lockDueForRedispatch(properties.batchSize());
        int dispatched = 0;
        for (NotificationDeliveryRepository.DueRecord item : due) {
            DeliveryRecord delivery = item.delivery();
            if (!deliveries.markPendingForRedispatch(
                    delivery.id(), item.previousStatus(), delivery.attempts())) {
                continue;
            }
            String reason = item.previousStatus() == NotificationDeliveryStatus.PROCESSING
                    ? "PROCESSING_LEASE_EXPIRED"
                    : "RETRY_DUE";
            appendHistory(delivery, NotificationDeliveryStatus.PENDING, reason, null, null, WORKER_ACTOR);
            appendWakeup(delivery);
            dispatched++;
        }
        return dispatched;
    }

    void cancelUnsentForDestination(UUID destinationId, String actorRef) {
        for (DeliveryRecord cancelled : deliveries.cancelUnsentForDestination(
                destinationId, "SUPPRESSED_CONFIG_DISABLED")) {
            appendHistory(cancelled, NotificationDeliveryStatus.CANCELLED,
                    "SUPPRESSED_CONFIG_DISABLED", null, null, actorRef);
        }
    }

    void cancelUnsentForRule(UUID ruleId, String actorRef) {
        for (DeliveryRecord cancelled : deliveries.cancelUnsentForRule(
                ruleId, "SUPPRESSED_CONFIG_DISABLED")) {
            appendHistory(cancelled, NotificationDeliveryStatus.CANCELLED,
                    "SUPPRESSED_CONFIG_DISABLED", null, null, actorRef);
        }
    }

    NotificationWorkerProperties properties() {
        return properties;
    }

    private void appendWakeup(DeliveryRecord delivery) {
        outbox.append(
                OUTBOX_TYPE,
                AGGREGATE_TYPE,
                delivery.id(),
                wakeupPayload(delivery.id()),
                delivery.correlationId());
    }

    private String wakeupPayload(UUID deliveryId) {
        try {
            return json.writeValueAsString(Map.of("deliveryId", deliveryId.toString()));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Notification wake-up payload could not be serialized.", exception);
        }
    }

    private void appendHistory(
            DeliveryRecord delivery,
            NotificationDeliveryStatus status,
            String reason,
            String errorCategory,
            String errorCode,
            String actorRef) {
        deliveries.appendHistory(
                delivery.id(), status, delivery.attempts(), reason, errorCategory, errorCode, actorRef);
    }

    private RouteState routeStateForReplay(DeliveryRecord delivery) {
        return currentRouteState(delivery);
    }

    private static boolean matches(NotificationRouteView route, NormalizedIntent intent) {
        if (!route.eventTypes().contains(intent.eventType())) return false;
        return route.severityFilters().isEmpty()
                || (intent.severity() != null && route.severityFilters().contains(intent.severity()));
    }

    private NormalizedIntent normalize(NotificationIntent input) {
        if (input == null) throw ApiProblemException.validationFailed("Notification intent is required.");
        String intentKey = requiredText(input.intentKey(), "Notification intent key", 160);
        String eventType = identifier(input.eventType(), "Notification event type");
        String severity = input.severity() == null ? null : identifier(input.severity(), "Notification severity");
        String correlationId = requiredText(input.correlationId(), "Notification correlation id", 128);
        String payload = requiredText(input.payloadJson(), "Notification payload", MAX_PAYLOAD_LENGTH);
        try {
            JsonNode root = json.readTree(payload);
            if (root == null || !root.isObject()) {
                throw ApiProblemException.validationFailed("Notification payload must be a JSON object.");
            }
            payload = json.writeValueAsString(root);
        } catch (JacksonException exception) {
            throw ApiProblemException.validationFailed("Notification payload must be valid JSON.");
        }
        return new NormalizedIntent(intentKey, eventType, severity, payload, correlationId);
    }

    private static String identifier(String value, String field) {
        String normalized = requiredText(value, field, 64);
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw ApiProblemException.validationFailed(field + " is invalid.");
        }
        return normalized;
    }

    private static String requiredText(String value, String field, int maxLength) {
        String normalized = normalizeOptional(value, maxLength);
        if (normalized == null) throw ApiProblemException.validationFailed(field + " is required.");
        return normalized;
    }

    private static String normalizeOptional(String value, int maxLength) {
        if (value == null) return null;
        String normalized = value.strip();
        if (normalized.isEmpty()) return null;
        if (normalized.length() > maxLength || normalized.indexOf('\n') >= 0 || normalized.indexOf('\r') >= 0) {
            throw ApiProblemException.validationFailed("Notification value is invalid.");
        }
        return normalized;
    }

    private static String requiredReason(String value) {
        return Objects.requireNonNullElse(normalizeOptional(value, 128), "DELIVERY_FAILED");
    }

    private static String errorCategory(String value) {
        return Objects.requireNonNullElse(normalizeOptional(value, 32), "PERMANENT");
    }

    private static String errorCode(String value) {
        return Objects.requireNonNullElse(normalizeOptional(value, 128), "UNSPECIFIED");
    }

    private static void requireManageNotifications(Authentication actor) {
        boolean allowed = actor != null
                && actor.isAuthenticated()
                && actor.getAuthorities().stream()
                        .anyMatch(authority -> MANAGE_NOTIFICATIONS.equals(authority.getAuthority()));
        if (!allowed) throw ApiProblemException.accessDenied();
    }

    enum RouteState {
        ENABLED,
        CONFIG_DISABLED,
        CONFIG_MISSING,
        CONFIG_CHANGED,
        NO_LONGER_MATCHES
    }

    private record NormalizedIntent(
            String intentKey,
            String eventType,
            String severity,
            String payloadJson,
            String correlationId) {
    }
}
