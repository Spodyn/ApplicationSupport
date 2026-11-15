package com.unifiedsupportinbox.cases;

import com.unifiedsupportinbox.integration.IntegrationProvider;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Serializes changes to active Case generations for the exact provider context. */
@Component
public class ProviderCaseContextLock {
    private final JdbcTemplate jdbc;

    public ProviderCaseContextLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void acquire(UUID integrationId, UUID channelId, IntegrationProvider provider,
            String conversationId, String threadKey) {
        String thread = threadKey == null ? "" : threadKey;
        String key = "case-context:" + integrationId + ":" + channelId + ":" + provider.name()
                + ":" + conversationId.length() + ":" + conversationId + ":" + thread;
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> { }, key);
    }
}
