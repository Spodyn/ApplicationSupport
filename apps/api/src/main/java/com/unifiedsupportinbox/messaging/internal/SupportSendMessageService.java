package com.unifiedsupportinbox.messaging.internal;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.IdempotencyResponse;
import com.unifiedsupportinbox.IdempotencyResult;
import com.unifiedsupportinbox.IdempotentCommandExecutor;
import com.unifiedsupportinbox.OutboxEventStore;
import com.unifiedsupportinbox.messaging.MessageBodyFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
class SupportSendMessageService {

    static final String OUTBOX_TYPE = "message.send_requested";
    private static final String AGGREGATE_TYPE = "message";
    private static final String COMMAND_SCOPE = "case.send-message";

    private final JdbcTemplate jdbc;
    private final OutboxEventStore outbox;
    private final IdempotentCommandExecutor idempotency;
    private final ObjectMapper json;

    SupportSendMessageService(
            JdbcTemplate jdbc,
            OutboxEventStore outbox,
            IdempotentCommandExecutor idempotency,
            ObjectMapper json) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.idempotency = idempotency;
        this.json = json;
    }

    IdempotencyResult send(
            UUID caseId,
            UUID userId,
            String idempotencyKey,
            String body,
            MessageBodyFormat bodyFormat,
            String correlationId) {
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(userId, "userId");
        String normalizedBody = requireBody(body);
        MessageBodyFormat normalizedFormat = bodyFormat == null ? MessageBodyFormat.PLAIN_TEXT : bodyFormat;
        String normalizedCorrelationId = requireText(correlationId, "correlationId", 128);

        Map<String, Object> canonicalRequest = Map.of(
                "caseId", caseId.toString(),
                "body", normalizedBody,
                "bodyFormat", normalizedFormat.name());

        return idempotency.execute(
                userId,
                COMMAND_SCOPE + ":" + caseId,
                idempotencyKey,
                canonicalRequest,
                () -> executeSend(caseId, userId, normalizedBody, normalizedFormat, normalizedCorrelationId));
    }

    private IdempotencyResponse executeSend(
            UUID caseId,
            UUID userId,
            String body,
            MessageBodyFormat bodyFormat,
            String correlationId) {
        CaseSendContext context = jdbc.query("""
                SELECT owner_user_id, status, external_thread_key
                FROM cases
                WHERE id = ?
                FOR UPDATE
                """, (rs, rowNum) -> new CaseSendContext(
                rs.getObject("owner_user_id", UUID.class),
                rs.getString("status"),
                rs.getString("external_thread_key")), caseId)
                .stream()
                .findFirst()
                .orElseThrow(() -> ApiProblemException.notFound("Case was not found."));

        if (!"VERIFICATION".equals(context.status())) {
            throw ApiProblemException.conflict("Messages can only be sent by the current owner while the Case is in VERIFICATION.");
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
                    correlation_id
                ) VALUES (?, NULL, ?, 'SUPPORT', ?, NULL, NULL, ?, ?, FALSE, 'QUEUED', NULL, NULL, NULL, ?)
                RETURNING id
                """, UUID.class,
                caseId,
                context.externalThreadKey(),
                userId,
                body,
                bodyFormat.name(),
                correlationId);

        if (messageId == null) {
            throw new IllegalStateException("Support Message insert returned no id.");
        }

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
        if (externalThreadKey != null) {
            payload.put("externalThreadKey", externalThreadKey);
        }
        return payload.toString();
    }

    private static String requireBody(String value) {
        if (value == null || value.isBlank()) {
            throw ApiProblemException.validationFailed("Message body must not be blank.");
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

    private record CaseSendContext(UUID ownerUserId, String status, String externalThreadKey) {
    }
}
