package com.unifiedsupportinbox.messaging.internal;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.IdempotencyResult;
import com.unifiedsupportinbox.IdempotentCommandExecutor;
import com.unifiedsupportinbox.messaging.MessageBodyFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Compatibility adapter for the deprecated Ask Customer endpoint. */
@Service
class AskCustomerService {
    private final IdempotentCommandExecutor idempotency;
    private final SupportSendMessageService replies;

    AskCustomerService(IdempotentCommandExecutor idempotency, SupportSendMessageService replies) {
        this.idempotency = idempotency;
        this.replies = replies;
    }

    IdempotencyResult ask(
            UUID caseId,
            UUID userId,
            String idempotencyKey,
            String message,
            MessageBodyFormat bodyFormat,
            Long waitingMinutes,
            String correlationId) {
        if (message == null || message.isBlank()) {
            throw ApiProblemException.validationFailed("Ask Customer message must not be blank.");
        }
        if (waitingMinutes != null && (waitingMinutes < 60 || waitingMinutes > 43200)) {
            throw ApiProblemException.validationFailed("waitingMinutes must be between 60 and 43200.");
        }
        MessageBodyFormat format = bodyFormat == null ? MessageBodyFormat.PLAIN_TEXT : bodyFormat;
        long waitingSeconds = (waitingMinutes == null ? 1440L : waitingMinutes) * 60;

        // Keep the original scope and fingerprint so pre-upgrade retries still replay.
        return idempotency.execute(userId, "case.ask-customer:" + caseId, idempotencyKey,
                new CanonicalAskRequest(caseId, message, format.name(), waitingSeconds),
                () -> replies.executeSend(caseId, userId, message, format, List.of(), correlationId));
    }

    private record CanonicalAskRequest(UUID caseId, String message, String bodyFormat, long waitingSeconds) {}
}
