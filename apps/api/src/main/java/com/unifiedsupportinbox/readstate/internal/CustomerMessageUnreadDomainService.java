package com.unifiedsupportinbox.readstate.internal;

import com.unifiedsupportinbox.OutboxEventStore;
import com.unifiedsupportinbox.readstate.CustomerMessageUnreadService;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
class CustomerMessageUnreadDomainService implements CustomerMessageUnreadService {

    static final String OUTBOX_TYPE = "case.unread_changed";
    private static final String AGGREGATE_TYPE = "case";

    private final JdbcTemplate jdbc;
    private final OutboxEventStore outbox;
    private final ObjectMapper json;

    CustomerMessageUnreadDomainService(JdbcTemplate jdbc, OutboxEventStore outbox, ObjectMapper json) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.json = json;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void customerMessageCreated(UUID caseId, UUID messageId, String correlationId) {
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(messageId, "messageId");
        requireText(correlationId, "correlationId", 128);
        verifyCustomerMessage(caseId, messageId);

        jdbc.update("""
                INSERT INTO case_read_states (
                    user_id,
                    case_id,
                    last_read_message_id,
                    last_read_at,
                    updated_at
                )
                SELECT u.id,
                       ?,
                       NULL,
                       NULL,
                       CURRENT_TIMESTAMP
                FROM users u
                WHERE u.active = TRUE
                  AND u.role IN ('USER', 'ADMIN')
                  AND (u.valid_from IS NULL OR u.valid_from <= CURRENT_TIMESTAMP)
                  AND (u.valid_until IS NULL OR u.valid_until > CURRENT_TIMESTAMP)
                ON CONFLICT (user_id, case_id) DO NOTHING
                """, caseId);

        outbox.append(
                OUTBOX_TYPE,
                AGGREGATE_TYPE,
                caseId,
                unreadChangedPayload(caseId, messageId),
                correlationId);
    }

    private void verifyCustomerMessage(UUID caseId, UUID messageId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*)
                FROM messages
                WHERE id = ?
                  AND case_id = ?
                  AND kind = 'CUSTOMER'
                  AND inbound = TRUE
                """, Integer.class, messageId, caseId);
        if (count == null || count != 1) {
            throw new IllegalArgumentException(
                    "Message must be an inbound CUSTOMER Message belonging to the requested Case.");
        }
    }

    private String unreadChangedPayload(UUID caseId, UUID messageId) {
        ObjectNode payload = json.createObjectNode();
        payload.put("caseId", caseId.toString());
        payload.put("messageId", messageId.toString());
        payload.put("reason", "CUSTOMER_MESSAGE");
        return payload.toString();
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
}
