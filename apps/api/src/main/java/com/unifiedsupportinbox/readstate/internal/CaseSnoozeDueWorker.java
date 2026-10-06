package com.unifiedsupportinbox.readstate.internal;

import com.unifiedsupportinbox.OutboxEventStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Processes due personal snoozes without changing global Case workflow state.
 */
@Component
@ConditionalOnProperty(prefix = "usi.readstate.snooze-worker", name = "enabled", havingValue = "true")
class CaseSnoozeDueWorker {

    static final String OUTBOX_TYPE = "case.snooze_due";
    private static final String AGGREGATE_TYPE = "case";
    private static final int BATCH_SIZE = 100;

    private final JdbcTemplate jdbc;
    private final OutboxEventStore outbox;
    private final ObjectMapper json;

    CaseSnoozeDueWorker(JdbcTemplate jdbc, OutboxEventStore outbox, ObjectMapper json) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.json = json;
    }

    @Scheduled(
            fixedDelayString = "${usi.readstate.snooze-worker.poll-interval:1s}",
            initialDelayString = "${usi.readstate.snooze-worker.initial-delay:1s}")
    @Transactional
    void wakeDue() {
        List<DueSnooze> due = jdbc.query("""
                SELECT case_id, user_id, until_at
                FROM case_snoozes
                WHERE until_at <= CURRENT_TIMESTAMP
                ORDER BY until_at, case_id, user_id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """, (rs, rowNum) -> new DueSnooze(
                rs.getObject("case_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getTimestamp("until_at").toInstant()), BATCH_SIZE);

        for (DueSnooze snooze : due) {
            int deleted = jdbc.update("""
                    DELETE FROM case_snoozes
                    WHERE case_id = ? AND user_id = ? AND until_at = ?
                    """, snooze.caseId(), snooze.userId(), Timestamp.from(snooze.until()));
            if (deleted == 1) {
                outbox.append(
                        OUTBOX_TYPE,
                        AGGREGATE_TYPE,
                        snooze.caseId(),
                        payload(snooze),
                        "snooze-due-" + UUID.randomUUID());
            }
        }
    }

    private String payload(DueSnooze snooze) {
        ObjectNode payload = json.createObjectNode();
        payload.put("caseId", snooze.caseId().toString());
        payload.put("userId", snooze.userId().toString());
        payload.put("snoozedUntil", snooze.until().toString());
        payload.put("dueAt", Instant.now().toString());
        return payload.toString();
    }

    private record DueSnooze(UUID caseId, UUID userId, Instant until) {}
}
