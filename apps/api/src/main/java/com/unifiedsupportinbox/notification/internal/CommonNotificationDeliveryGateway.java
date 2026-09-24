package com.unifiedsupportinbox.notification.internal;

import com.unifiedsupportinbox.integration.IntegrationProvider;
import com.unifiedsupportinbox.notification.NotificationDeliveryGateway;
import com.unifiedsupportinbox.notification.NotificationProviderClient;
import com.unifiedsupportinbox.notification.NotificationProviderException;
import java.util.List;
import org.springframework.stereotype.Component;

/** Routes an escalation notification to exactly one provider adapter and normalizes its outcome. */
@Component
final class CommonNotificationDeliveryGateway implements NotificationDeliveryGateway {

    private final List<NotificationProviderClient> clients;

    CommonNotificationDeliveryGateway(List<NotificationProviderClient> configuredClients) {
        this.clients = List.copyOf(configuredClients);
    }

    @Override
    public DeliveryResult deliver(DeliveryCommand command) {
        if (blank(command.targetRef())) return DeliveryResult.permanentFailure("INVALID_TARGET");
        if (blank(command.correlationId())) return DeliveryResult.permanentFailure("MISSING_CORRELATION_ID");

        List<NotificationProviderClient> matching = clients.stream()
                .filter(candidate -> candidate.supports(command.provider()))
                .toList();
        if (matching.isEmpty()) return DeliveryResult.transientFailure("PROVIDER_UNAVAILABLE", null);
        if (matching.size() != 1) return DeliveryResult.permanentFailure("AMBIGUOUS_PROVIDER_CLIENT");
        NotificationProviderClient client = matching.getFirst();
        if (!client.supportsTarget(command.targetRef())) return DeliveryResult.permanentFailure("INVALID_TARGET");

        try {
            NotificationProviderClient.ProviderMessageRef result = client.send(
                    new NotificationProviderClient.ProviderNotification(
                            command.idempotencyKey(), command.targetRef(), command.eventType(),
                            command.severity(), command.payloadJson(), command.correlationId()));
            if (result == null || blank(result.value())) return DeliveryResult.transientFailure("INVALID_PROVIDER_RESPONSE", null);
            return DeliveryResult.sent(result.value());
        } catch (NotificationProviderException failure) {
            return failure.kind() == NotificationProviderException.Kind.PERMANENT
                    ? DeliveryResult.permanentFailure(failure.errorCode())
                    : DeliveryResult.transientFailure(failure.errorCode(), failure.retryAfter());
        } catch (RuntimeException failure) {
            return DeliveryResult.transientFailure("PROVIDER_EXCEPTION", null);
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
