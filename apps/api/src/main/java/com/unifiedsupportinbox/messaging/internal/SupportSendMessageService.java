package com.unifiedsupportinbox.messaging.internal;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.IdempotencyResponse;
import com.unifiedsupportinbox.IdempotencyResult;
import com.unifiedsupportinbox.IdempotentCommandExecutor;
import com.unifiedsupportinbox.OutboxEventStore;
import com.unifiedsupportinbox.messaging.MessageBodyFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
class SupportSendMessageService {

    static final String OUTBOX_TYPE = "message.send_requested";
    private static final String AGGREGATE_TYPE = "message";
    private static final String COMMAND_SCOPE = "case.send-message";
    private static final String ASK_COMMAND_SCOPE = "case.ask-customer";
    private static final int MAX_ATTACHMENTS = 10;
    private static final long MAX_ATTACHMENT_BYTES = 50L * 1024L * 1024L;

    private final JdbcTemplate jdbc;
    private final OutboxEventStore outbox;
    private final IdempotentCommandExecutor idempotency;
    private final ObjectMapper json;
    private final MessageCreatedOutboxPublisher messageCreated;

    SupportSendMessageService(
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

    IdempotencyResult send(
            UUID caseId,
            UUID userId,
            String idempotencyKey,
            String body,
            MessageBodyFormat bodyFormat,
            String correlationId) {
        return send(caseId, userId, idempotencyKey, body, bodyFormat, List.of(), correlationId);
    }

    IdempotencyResult ask(
            UUID caseId,
            UUID userId,
            String idempotencyKey,
            String body,
            MessageBodyFormat bodyFormat,
            Long waitingMinutes,
            String correlationId) {
        long normalizedWaitingMinutes = waitingMinutes == null ? 24L * 60L : waitingMinutes;
        if (normalizedWaitingMinutes < 60L || normalizedWaitingMinutes > 30L * 24L * 60L) {
            throw ApiProblemException.validationFailed("Waiting duration must be between 60 and 43200 minutes.");
        }
        return sendInternal(
                caseId,
                userId,
                idempotencyKey,
                body,
                bodyFormat,
                List.of(),
                correlationId,
                normalizedWaitingMinutes * 60L,
                ASK_COMMAND_SCOPE);
    }

    IdempotencyResult send(
            UUID caseId,
            UUID userId,
            String idempotencyKey,
            String body,
            MessageBodyFormat bodyFormat,
            List<UUID> attachmentIds,
            String correlationId) {
        return sendInternal(
                caseId,
                userId,
                idempotencyKey,
                body,
                bodyFormat,
                attachmentIds,
                correlationId,
                null,
                COMMAND_SCOPE);
    }

    private IdempotencyResult sendInternal(
            UUID caseId,
            UUID userId,
            String idempotencyKey,
            String body,
            MessageBodyFormat bodyFormat,
            List<UUID> attachmentIds,
            String correlationId,
            Long askWaitingSeconds,
            String commandScope) {
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(userId, "userId");
        String normalizedBody = requireBody(body);
        MessageBodyFormat normalizedFormat = bodyFormat == null ? MessageBodyFormat.PLAIN_TEXT : bodyFormat;
        List<UUID> normalizedAttachments = normalizeAttachmentIds(attachmentIds);
        String normalizedCorrelationId = requireText(correlationId, "correlationId", 128);

        Map<String, Object> canonicalRequest = new java.util.LinkedHashMap<>();
        canonicalRequest.put("caseId", caseId.toString());
        canonicalRequest.put("body", normalizedBody);
        canonicalRequest.put("bodyFormat", normalizedFormat.name());
        canonicalRequest.put("attachmentIds", normalizedAttachments.stream().map(UUID::toString).toList());
        if (askWaitingSeconds != null) canonicalRequest.put("askWaitingSeconds", askWaitingSeconds);

        return idempotency.execute(
                userId,
                commandScope + ":" + caseId,
                idempotencyKey,
                canonicalRequest,
                () -> executeSend(
                        caseId,
                        userId,
                        normalizedBody,
                        normalizedFormat,
                        normalizedAttachments,
                        normalizedCorrelationId,
                        askWaitingSeconds));
    }

    private IdempotencyResponse executeSend(
            UUID caseId,
            UUID userId,
            String body,
            MessageBodyFormat bodyFormat,
            List<UUID> attachmentIds,
            String correlationId,
            Long askWaitingSeconds) {
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

        lockAndValidateAttachments(caseId, attachmentIds);

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
                    ask_waiting_seconds,
                    provider_created_at,
                    edited_at,
                    deleted_at,
                    correlation_id
                ) VALUES (?, NULL, ?, 'SUPPORT', ?, NULL, NULL, ?, ?, FALSE, 'QUEUED', ?, NULL, NULL, NULL, ?)
                RETURNING id
                """, UUID.class,
                caseId,
                context.externalThreadKey(),
                userId,
                body,
                bodyFormat.name(),
                askWaitingSeconds,
                correlationId);

        if (messageId == null) {
            throw new IllegalStateException("Support Message insert returned no id.");
        }

        associateAttachments(messageId, caseId, attachmentIds);
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

    private void lockAndValidateAttachments(UUID caseId, List<UUID> attachmentIds) {
        if (attachmentIds.isEmpty()) return;
        String placeholders = attachmentIds.stream().map(ignored -> "?").collect(Collectors.joining(", "));
        Object[] args = attachmentIds.toArray();
        List<AttachmentCandidate> rows = jdbc.query("""
                SELECT id, case_id, message_id, size_bytes, scan_status
                FROM attachments
                WHERE id IN (%s)
                FOR UPDATE
                """.formatted(placeholders), (rs, rowNum) -> new AttachmentCandidate(
                rs.getObject("id", UUID.class),
                rs.getObject("case_id", UUID.class),
                rs.getObject("message_id", UUID.class),
                rs.getLong("size_bytes"),
                rs.getString("scan_status")), args);

        if (rows.size() != attachmentIds.size()) {
            throw ApiProblemException.validationFailed("One or more attachments do not exist.");
        }
        long totalBytes = 0;
        for (AttachmentCandidate attachment : rows) {
            if (!caseId.equals(attachment.caseId())) {
                throw ApiProblemException.accessDenied();
            }
            if (attachment.messageId() != null) {
                throw ApiProblemException.conflict("An attachment is already associated with a Message.");
            }
            if (!"CLEAN".equals(attachment.scanStatus())) {
                throw ApiProblemException.validationFailed("Only CLEAN attachments may be sent.");
            }
            totalBytes = Math.addExact(totalBytes, attachment.sizeBytes());
            if (totalBytes > MAX_ATTACHMENT_BYTES) {
                throw ApiProblemException.validationFailed("Attachments exceed the 50 MiB per-message limit.");
            }
        }
    }

    private void associateAttachments(UUID messageId, UUID caseId, List<UUID> attachmentIds) {
        if (attachmentIds.isEmpty()) return;
        String placeholders = attachmentIds.stream().map(ignored -> "?").collect(Collectors.joining(", "));
        Object[] args = new Object[attachmentIds.size() + 2];
        args[0] = messageId;
        args[1] = caseId;
        for (int index = 0; index < attachmentIds.size(); index++) {
            args[index + 2] = attachmentIds.get(index);
        }
        int updated = jdbc.update("""
                UPDATE attachments
                SET message_id = ?
                WHERE case_id = ?
                  AND message_id IS NULL
                  AND scan_status = 'CLEAN'
                  AND id IN (%s)
                """.formatted(placeholders), args);
        if (updated != attachmentIds.size()) {
            throw new IllegalStateException("Attachment association changed concurrently.");
        }
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

    private static List<UUID> normalizeAttachmentIds(List<UUID> values) {
        if (values == null || values.isEmpty()) return List.of();
        if (values.size() > MAX_ATTACHMENTS) {
            throw ApiProblemException.validationFailed("A Message may contain at most 10 attachments.");
        }
        if (values.stream().anyMatch(Objects::isNull)) {
            throw ApiProblemException.validationFailed("attachmentIds must not contain null values.");
        }
        Set<UUID> distinct = new HashSet<>(values);
        if (distinct.size() != values.size()) {
            throw ApiProblemException.validationFailed("attachmentIds must not contain duplicates.");
        }
        return List.copyOf(values);
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

    private record AttachmentCandidate(
            UUID id,
            UUID caseId,
            UUID messageId,
            long sizeBytes,
            String scanStatus) {
    }
}
