package com.unifiedsupportinbox;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class InboundEventOutcomeStore {

    public static final String IGNORED_BY_CHANNEL = "IGNORED_BY_CHANNEL";
    public static final String UNMAPPED_CHANNEL = "UNMAPPED_CHANNEL";
    public static final String INACTIVE_CHANNEL = "INACTIVE_CHANNEL";
    public static final String IGNORED_BOT_MESSAGE = "IGNORED_BOT_MESSAGE";
    public static final String UNSUPPORTED_PROVIDER_EVENT = "UNSUPPORTED_PROVIDER_EVENT";

    private static final Set<String> ALLOWED_OUTCOMES = Set.of(
            IGNORED_BY_CHANNEL,
            UNMAPPED_CHANNEL,
            INACTIVE_CHANNEL,
            IGNORED_BOT_MESSAGE,
            UNSUPPORTED_PROVIDER_EVENT);

    private final JdbcTemplate jdbc;

    public InboundEventOutcomeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markIgnoredByChannel(UUID inboundEventId) {
        mark(inboundEventId, IGNORED_BY_CHANNEL);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void mark(UUID inboundEventId, String outcome) {
        Objects.requireNonNull(inboundEventId, "inboundEventId");
        Objects.requireNonNull(outcome, "outcome");
        if (!ALLOWED_OUTCOMES.contains(outcome)) {
            throw new IllegalArgumentException("Unsupported inbound processing outcome: " + outcome);
        }
        int updated = jdbc.update("""
                UPDATE inbound_events
                SET processing_outcome = ?
                WHERE id = ?
                  AND status = 'PROCESSING'
                """, outcome, inboundEventId);
        if (updated != 1) {
            throw new IllegalStateException(
                    "Inbound event is not in PROCESSING state: " + inboundEventId);
        }
    }
}
