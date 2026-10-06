package com.unifiedsupportinbox.messaging.internal;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.IdempotencyResponse;
import com.unifiedsupportinbox.IdempotencyResult;
import com.unifiedsupportinbox.IdempotentCommandExecutor;
import com.unifiedsupportinbox.OutboxEventStore;
import com.unifiedsupportinbox.messaging.MessageBodyFormat;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Persists the logical Ask Customer support Message and durable provider-delivery
 * intent. The Case intentionally stays VERIFICATION/owned until delivery success.
 */
@Service
class AskCustomerService {

    private static final String COMMAND_SCOPE = "case.ask-customer";
    private static final String OUTBOX_TYPE = "message.send_requested";
    private static final String AGGREGATE_TYPE = "message";
    private static final Duration DEFAULT_WAIT = Duration.ofHours(24);
    private static final Duration MIN_WAIT = Duration.ofHours(1);
    private static final Duration MAX_WAIT = Duration.ofDays(30);

    private final JdbcTemplate jdbc;
    private final OutboxEventStore outbox;
    private final IdempotentCommandExecutor idempotency;
    private final ObjectMapper json;
    private final MessageCreatedOutboxPublisher messageCreated;

    AskCustomerService(
            JdbcTemplate jdbc,
            OutboxEventStore outbox,
            IdempotentCommandExecutor idempotency,
            ObjectMapper json,
            MessageCreatedOutboxPublisher messageCreated) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.idempotency = idempotency;
        this.json = json;
        this.messageCreated = messageCreated;
    }

    IdempotencyResult ask(
            UUID caseId,
            UUID userId,
            String idempotencyKey,
            String message,
            MessageBodyFormat bodyFormat,
            Long waitingMinutes,
            String correlationId) {
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(userId, "userId");
        String body = requireBody(message);
        MessageBodyFormat format = bodyFormat == null ? MessageBodyFormat.PLAIN_TEXT : bodyFormat;
        Duration wait = normalizeWait(waitingMinutes);
        String normalizedCorrelationId = requireText(correlationId, "correlationId", 128);

        return idempotency.execute(
                userId,
                COMMAND_SCOPE + ":" + caseId,
                idempotencyKey,
                new CanonicalAskRequest(caseId, body, format.name(), wait.toSeconds()),
                () -> executeAsk(caseId, userId, body, format, wait, normalizedCorrelationId));
    }

    private IdempotencyResponse executeAsk(
            UUID caseId,
            UUID userId,
            String body,
            MessageBodyFormat bodyFormat,
            Duration waiting,
            String correlationId) {
        CaseContext context = jdbc.query("""
                SELECT owner_user_id, status, external_thread_key
                FROM cases
                WHERE id = ?
                FOR UPDATE
                """, (rs, rowNum) -> new CaseContext(
                    rs.getObject("owner_user_id", UUID.class),
                    rs.getString("status"),
                    rs.getString("external_thread_key")),
                caseId).stream().findFirst()
                .orElseThrow(() -> ApiProblemException.notFound("Case was not found."));

        if (!"VERIFICATION".equals(context.status())) {
            throw ApiProblemException.conflict("Ask Customer is only available while the Case is in VERIFICATION.");
        }
        if (!userId.equals(context.ownerUserId())) {
            throw ApiProblemException.accessDenied();
        }

        UUID messageId = jdbc.queryForObject("""
                INSERT INTO messages (
                    case_id,
                    external_message_id,
                    external_thread_key,
                    kind,
                    author_user_id,
                    author_external_id,
                    author_name,
                    body,
                    body_format,
                    inbound,
                    delivery_status,
                    provider_created_at,
                    edited_at,
                    deleted_at,
                    correlation_id,
                    ask_waiting_seconds
                ) VALUES (?, NULL, ?, 'SUPPORT', ?, NULL, NULL, ?, ?, FALSE, 'QUEUED',
                          NULL, NULL, NULL, ?, ?)
                RETURNING id
                """, UUID.class,
                caseId,
                context.externalThreadKey(),
                userId,
                body,
                bodyFormat.name(),
                correlationId,
                waiting.toSeconds());

        if (messageId == null) {
            throw new IllegalStateException("Ask Customer Message insert returned no id.");
        }

        messageCreated.publish(messageId, caseId, correlationId);
        outbox.append(
                OUTBOX_TYPE,
                AGGREGATE_TYPE,
                messageId,
                deliveryPayload(messageId, caseId, context.externalThreadKey()),
                correlationId);

        ObjectNode response = json.createObjectNode();
        response.put("messageId", messageId.toString());
        response.put("deliveryStatus", "QUEUED");
        return new IdempotencyResponse(202, response);
    }

    private String deliveryPayload(UUID messageId, UUID caseId, String externalThreadKey) {
        ObjectNode payload = json.createObjectNode();
        payload.put("messageId", messageId.toString());
        payload.put("caseId", caseId.toString());
        if (externalThreadKey != null) payload.put("externalThreadKey", externalThreadKey);
        return payload.toString();
    }

    private static Duration normalizeWait(Long waitingMinutes) {
        Duration value = waitingMinutes == null ? DEFAULT_WAIT : Duration.ofMinutes(waitingMinutes);
        if (value.compareTo(MIN_WAIT) < 0 || value.compareTo(MAX_WAIT) > 0) {
            throw ApiProblemException.validationFailed("waitingMinutes must be between 60 and 43200.");
        }
        return value;
    }

    private static String requireBody(String value) {
        if (value == null || value.isBlank()) {
            throw ApiProblemException.validationFailed("Ask Customer message must not be blank.");
        }
        return value;
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank.");
        }
        String normalized = value.strip();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds max length " + maxLength + ".");
        }
        return normalized;
    }

    private record CaseContext(UUID ownerUserId, String status, String externalThreadKey) {}
    private record CanonicalAskRequest(UUID caseId, String message, String bodyFormat, long waitingSeconds) {}
}
