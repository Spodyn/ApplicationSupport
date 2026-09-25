package com.unifiedsupportinbox.messaging.internal;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.IdempotencyResponse;
import com.unifiedsupportinbox.IdempotencyResult;
import com.unifiedsupportinbox.IdempotentCommandExecutor;
import com.unifiedsupportinbox.OutboxEventStore;
import com.unifiedsupportinbox.messaging.MessageDeliveryChanged;
import com.unifiedsupportinbox.messaging.MessageDeliveryStatus;
import com.unifiedsupportinbox.messaging.internal.MessageDeliveryRepository.DeliveryAttemptRecord;
import com.unifiedsupportinbox.messaging.internal.MessageDeliveryRepository.DeliveryMessageRecord;
import com.unifiedsupportinbox.messaging.internal.MessageDeliveryRepository.DueDeliveryRecord;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
class MessageDeliveryService {

    private static final String AGGREGATE_TYPE = "message";
    private static final String RETRY_COMMAND_SCOPE = "message.retry";
    private static final String TRANSIENT = "TRANSIENT";
    private static final String PERMANENT = "PERMANENT";

    private final MessageDeliveryRepository repository;
    private final MessageDeliveryWorkerProperties properties;
    private final OutboxEventStore outbox;
    private final IdempotentCommandExecutor idempotency;
    private final ObjectMapper json;
    private final ApplicationEventPublisher events;

    MessageDeliveryService(
            MessageDeliveryRepository repository,
            MessageDeliveryWorkerProperties properties,
            OutboxEventStore outbox,
            IdempotentCommandExecutor idempotency,
            ObjectMapper json,
            ApplicationEventPublisher events) {
        this.repository = repository;
        this.properties = properties;
        this.outbox = outbox;
        this.idempotency = idempotency;
        this.json = json;
        this.events = events;
    }

    @Transactional
    DeliveryClaim claim(UUID messageId) {
        DeliveryMessageRecord message = repository.lockMessage(messageId).orElse(null);
        if (message == null || message.deliveryStatus() != MessageDeliveryStatus.QUEUED) {
            return null;
        }
        DeliveryAttemptRecord latest = message.latestAttempt();
        if (latest != null && latest.finishedAt() != null && latest.nextRetryAt() != null) {
            return null;
        }
        if (latest != null && latest.finishedAt() == null) {
            return null;
        }

        int attemptNo = message.attemptCount() + 1;
        DeliveryAttemptRecord attempt = repository.insertAttempt(
                message.messageId(), attemptNo, properties.claimLease());
        if (!repository.markSending(message.messageId())) {
            throw new IllegalStateException("Message lost QUEUED state while starting delivery: " + message.messageId());
        }
        publish(message, MessageDeliveryStatus.SENDING, null, null, null);
        return new DeliveryClaim(message, attempt);
    }

    @Transactional
    void markSuccess(DeliveryClaim claim, MessageDeliveryStatus status, String providerMessageRef) {
        Objects.requireNonNull(claim, "claim");
        if (status != MessageDeliveryStatus.SENT && status != MessageDeliveryStatus.DELIVERED) {
            throw new IllegalArgumentException("Successful delivery must become SENT or DELIVERED.");
        }
        DeliveryMessageRecord current = lockedClaim(claim);
        String normalizedRef = normalizeOptional(providerMessageRef, 512);
        if (!repository.finishAttemptSuccess(current.messageId(), claim.attempt().attemptNo(), normalizedRef)
                || !repository.markSuccess(current.messageId(), status, normalizedRef)) {
            throw new IllegalStateException("Message delivery claim was lost before success checkpoint: " + current.messageId());
        }
        publish(current, status, null, null, null);
    }

    @Transactional
    DeliveryFailureResult markTransientFailure(
            DeliveryClaim claim,
            String errorCode,
            Duration computedDelay) {
        Objects.requireNonNull(claim, "claim");
        Objects.requireNonNull(computedDelay, "computedDelay");
        DeliveryMessageRecord current = lockedClaim(claim);
        String code = errorCode(errorCode);
        Instant nextRetryAt = retryAt(current, claim.attempt().attemptNo(), computedDelay);
        MessageDeliveryStatus state = nextRetryAt == null
                ? MessageDeliveryStatus.FAILED
                : MessageDeliveryStatus.QUEUED;
        if (!repository.finishAttemptFailure(
                        current.messageId(), claim.attempt().attemptNo(), TRANSIENT, code, nextRetryAt)
                || !repository.markFailureState(current.messageId(), state)) {
            throw new IllegalStateException("Message delivery claim was lost before failure checkpoint: " + current.messageId());
        }
        publish(current, state, TRANSIENT, code, nextRetryAt);
        return nextRetryAt == null
                ? DeliveryFailureResult.FAILED
                : DeliveryFailureResult.RETRY_SCHEDULED;
    }

    @Transactional
    void markPermanentFailure(DeliveryClaim claim, String errorCode) {
        Objects.requireNonNull(claim, "claim");
        DeliveryMessageRecord current = lockedClaim(claim);
        String code = errorCode(errorCode);
        if (!repository.finishAttemptFailure(
                        current.messageId(), claim.attempt().attemptNo(), PERMANENT, code, null)
                || !repository.markFailureState(current.messageId(), MessageDeliveryStatus.FAILED)) {
            throw new IllegalStateException("Message delivery claim was lost before permanent failure checkpoint: " + current.messageId());
        }
        publish(current, MessageDeliveryStatus.FAILED, PERMANENT, code, null);
    }

    @Transactional
    int redispatchDue() {
        int dispatched = 0;
        for (DueDeliveryRecord due : repository.lockDue(properties.batchSize())) {
            if (due.deliveryStatus() == MessageDeliveryStatus.SENDING) {
                if (!repository.recoverExpiredClaim(due.messageId(), due.attempt().attemptNo())) {
                    continue;
                }
                appendWakeup(due.messageId(), due.caseId(), null, due.correlationId());
                events.publishEvent(new MessageDeliveryChanged(
                        due.messageId(),
                        due.caseId(),
                        MessageDeliveryStatus.QUEUED,
                        TRANSIENT,
                        "WORKER_LEASE_EXPIRED",
                        null,
                        due.correlationId()));
                dispatched++;
                continue;
            }
            if (repository.markRetryWakeDispatched(due.messageId(), due.attempt().attemptNo())) {
                appendWakeup(due.messageId(), due.caseId(), null, due.correlationId());
                dispatched++;
            }
        }
        return dispatched;
    }

    IdempotencyResult retry(
            UUID messageId,
            UUID userId,
            String idempotencyKey,
            String correlationId) {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(userId, "userId");
        String normalizedCorrelationId = requiredText(correlationId, "correlationId", 128);
        return idempotency.execute(
                userId,
                RETRY_COMMAND_SCOPE + ":" + messageId,
                idempotencyKey,
                Map.of("messageId", messageId.toString()),
                () -> executeRetry(messageId, userId, normalizedCorrelationId));
    }

    private IdempotencyResponse executeRetry(UUID messageId, UUID userId, String correlationId) {
        DeliveryMessageRecord message = repository.lockMessage(messageId)
                .orElseThrow(() -> ApiProblemException.notFound("Message was not found."));
        if (message.deliveryStatus() != MessageDeliveryStatus.FAILED) {
            throw ApiProblemException.conflict("Only failed messages can be retried manually.");
        }
        if (!"VERIFICATION".equals(message.caseStatus()) || !userId.equals(message.caseOwnerUserId())) {
            throw ApiProblemException.accessDenied();
        }
        DeliveryAttemptRecord latest = message.latestAttempt();
        if (latest == null || !manualRetryAllowed(latest)) {
            throw ApiProblemException.conflict("This delivery failure is not eligible for manual retry.");
        }
        if (!repository.queueFailedForManualRetry(messageId)) {
            throw ApiProblemException.conflict("Message delivery state changed before retry.");
        }
        appendWakeup(message.messageId(), message.caseId(), message.externalThreadKey(), correlationId);
        events.publishEvent(new MessageDeliveryChanged(
                message.messageId(), message.caseId(), MessageDeliveryStatus.QUEUED,
                latest.errorCategory(), latest.errorCode(), null, correlationId));

        ObjectNode response = json.createObjectNode();
        response.put("messageId", message.messageId().toString());
        response.put("deliveryStatus", MessageDeliveryStatus.QUEUED.name());
        return new IdempotencyResponse(202, response);
    }

    MessageDeliveryWorkerProperties properties() {
        return properties;
    }

    private DeliveryMessageRecord lockedClaim(DeliveryClaim claim) {
        DeliveryMessageRecord current = repository.lockMessage(claim.message().messageId())
                .orElseThrow(() -> new IllegalStateException("Message disappeared during delivery."));
        DeliveryAttemptRecord latest = current.latestAttempt();
        if (current.deliveryStatus() != MessageDeliveryStatus.SENDING
                || latest == null
                || latest.attemptNo() != claim.attempt().attemptNo()
                || latest.finishedAt() != null) {
            throw new IllegalStateException("Message delivery claim is no longer current: " + current.messageId());
        }
        return current;
    }

    private Instant retryAt(DeliveryMessageRecord current, int attemptNo, Duration delay) {
        if (attemptNo >= properties.maxAutomaticAttempts()) {
            return null;
        }
        Instant firstAttempt = current.firstAttemptAt() == null
                ? current.createdAt()
                : current.firstAttemptAt();
        Instant deadline = firstAttempt.plus(properties.retryWindow());
        Instant candidate = Instant.now().plus(delay);
        return candidate.isBefore(deadline) ? candidate : null;
    }

    private void appendWakeup(
            UUID messageId,
            UUID caseId,
            String externalThreadKey,
            String correlationId) {
        ObjectNode payload = json.createObjectNode();
        payload.put("messageId", messageId.toString());
        payload.put("caseId", caseId.toString());
        if (externalThreadKey != null) payload.put("externalThreadKey", externalThreadKey);
        outbox.append(
                SupportSendMessageService.OUTBOX_TYPE,
                AGGREGATE_TYPE,
                messageId,
                payload.toString(),
                correlationId);
    }

    private void publish(
            DeliveryMessageRecord message,
            MessageDeliveryStatus status,
            String category,
            String code,
            Instant nextRetryAt) {
        events.publishEvent(new MessageDeliveryChanged(
                message.messageId(), message.caseId(), status, category, code, nextRetryAt,
                message.correlationId()));
    }

    private static boolean manualRetryAllowed(DeliveryAttemptRecord attempt) {
        return TRANSIENT.equals(attempt.errorCategory())
                || (PERMANENT.equals(attempt.errorCategory())
                    && "INTEGRATION_DISABLED".equals(attempt.errorCode()));
    }

    private static String errorCode(String value) {
        String normalized = normalizeOptional(value, 128);
        return normalized == null ? "UNSPECIFIED" : normalized;
    }

    private static String requiredText(String value, String field, int maxLength) {
        String normalized = normalizeOptional(value, maxLength);
        if (normalized == null) throw new IllegalArgumentException(field + " must not be blank.");
        return normalized;
    }

    private static String normalizeOptional(String value, int maxLength) {
        if (value == null) return null;
        String normalized = value.strip();
        if (normalized.isEmpty()) return null;
        if (normalized.length() > maxLength || normalized.indexOf('\n') >= 0 || normalized.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Delivery value is invalid.");
        }
        return normalized;
    }

    record DeliveryClaim(DeliveryMessageRecord message, DeliveryAttemptRecord attempt) {
    }

    enum DeliveryFailureResult {
        RETRY_SCHEDULED,
        FAILED
    }
}
