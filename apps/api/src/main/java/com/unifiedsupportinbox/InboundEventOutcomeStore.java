package com.unifiedsupportinbox;

import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class InboundEventOutcomeStore {

    public static final String IGNORED_BY_CHANNEL = "IGNORED_BY_CHANNEL";

    private final JdbcTemplate jdbc;

    public InboundEventOutcomeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markIgnoredByChannel(UUID inboundEventId) {
        Objects.requireNonNull(inboundEventId, "inboundEventId");
        int updated = jdbc.update("""
                UPDATE inbound_events
                SET processing_outcome = ?
                WHERE id = ?
                  AND status = 'PROCESSING'
                """, IGNORED_BY_CHANNEL, inboundEventId);
        if (updated != 1) {
            throw new IllegalStateException(
                    "Inbound event is not in PROCESSING state: " + inboundEventId);
        }
    }
}
