package com.unifiedsupportinbox.messaging;

import com.unifiedsupportinbox.integration.IntegrationProvider;
import java.time.Duration;
import java.util.UUID;

/**
 * Provider-specific outbound Message boundary. Implementations perform provider I/O and must
 * honor {@link DeliveryCommand#idempotencyKey()} for retries of the same logical Message.
 */
public interface MessageDeliveryProvider {

    boolean supports(IntegrationProvider provider);

    DeliveryResult deliver(DeliveryCommand command);

    /** Pure capability check. No provider HTTP may be performed here. */
    default MutationCapability mutationCapability(MutationTarget target, boolean hasAttachments, boolean hasText) {
        return new MutationCapability(false, false, "PROVIDER_UNSUPPORTED", "PROVIDER_UNSUPPORTED");
    }

    default MutationResult mutate(MutationCommand command) {
        return new MutationResult(MutationOutcome.FAILED, "PROVIDER_UNSUPPORTED", null);
    }

    record MutationTarget(String providerMessageRef, String conversationId, String senderId, String serviceUrl,
                          String contextType, String attachmentPayload, String credentialRef) {}
    record MutationCapability(boolean canEdit, boolean canDelete, String editUnavailable, String deleteUnavailable) {}
    record MutationCommand(UUID operationId, UUID messageId, UUID integrationId, IntegrationProvider provider,
                           String externalChannelId, String action, String body, MessageBodyFormat bodyFormat,
                           MutationTarget target) {}
    record MutationResult(MutationOutcome outcome, String errorCode, Duration retryAfter) {
        public static MutationResult confirmed() { return new MutationResult(MutationOutcome.CONFIRMED, null, null); }
        public static MutationResult failed(String code) { return new MutationResult(MutationOutcome.FAILED, code, null); }
        public static MutationResult retry(String code, Duration delay) { return new MutationResult(MutationOutcome.RETRY, code, delay); }
        public static MutationResult uncertain(String code) { return new MutationResult(MutationOutcome.UNCERTAIN, code, null); }
    }
    enum MutationOutcome { CONFIRMED, RETRY, FAILED, UNCERTAIN }

    record DeliveryCommand(
            UUID messageId,
            String idempotencyKey,
            UUID caseId,
            IntegrationProvider provider,
            UUID integrationId,
            String externalConversationId,
            String externalThreadKey,
            String body,
            MessageBodyFormat bodyFormat,
            String correlationId,
            String acceptedBodyRef,
            java.util.function.Consumer<String> recordAcceptedBody,
            java.util.function.Consumer<MutationTarget> recordMutationTarget) {

        public DeliveryCommand(UUID messageId, String idempotencyKey, UUID caseId, IntegrationProvider provider,
                UUID integrationId, String externalConversationId, String externalThreadKey, String body,
                MessageBodyFormat bodyFormat, String correlationId, String acceptedBodyRef,
                java.util.function.Consumer<String> recordAcceptedBody) {
            this(messageId, idempotencyKey, caseId, provider, integrationId, externalConversationId, externalThreadKey,
                    body, bodyFormat, correlationId, acceptedBodyRef, recordAcceptedBody, ignored -> { });
        }

        public DeliveryCommand(UUID messageId, String idempotencyKey, UUID caseId, IntegrationProvider provider,
                UUID integrationId, String externalConversationId, String externalThreadKey, String body,
                MessageBodyFormat bodyFormat, String correlationId) {
            this(messageId, idempotencyKey, caseId, provider, integrationId, externalConversationId,
                    externalThreadKey, body, bodyFormat, correlationId, null, ignored -> { });
        }
    }

    record DeliveryResult(
            Outcome outcome,
            String providerMessageRef,
            String errorCode,
            Duration retryAfter) {

        public static DeliveryResult sent(String providerMessageRef) {
            return new DeliveryResult(Outcome.SENT, providerMessageRef, null, null);
        }

        public static DeliveryResult delivered(String providerMessageRef) {
            return new DeliveryResult(Outcome.DELIVERED, providerMessageRef, null, null);
        }

        public static DeliveryResult transientFailure(String errorCode, Duration retryAfter) {
            return new DeliveryResult(Outcome.TRANSIENT_FAILURE, null, errorCode, retryAfter);
        }

        public static DeliveryResult permanentFailure(String errorCode) {
            return new DeliveryResult(Outcome.PERMANENT_FAILURE, null, errorCode, null);
        }
    }

    enum Outcome {
        SENT,
        DELIVERED,
        TRANSIENT_FAILURE,
        PERMANENT_FAILURE
    }
}
