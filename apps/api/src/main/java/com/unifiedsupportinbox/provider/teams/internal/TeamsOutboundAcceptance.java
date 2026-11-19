package com.unifiedsupportinbox.provider.teams.internal;

import com.unifiedsupportinbox.messaging.MessageDeliveryProvider.DeliveryCommand;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Actual HTTP acceptance, distinct from a provider-assigned message identifier. */
@Component
class TeamsOutboundAcceptance {
    private final JdbcTemplate jdbc;
    TeamsOutboundAcceptance(JdbcTemplate jdbc){this.jdbc=jdbc;}
    boolean accepted(DeliveryCommand command) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM teams_outbound_acceptance a
                  JOIN messages m ON m.id=a.message_id JOIN cases c ON c.id=m.case_id
                  JOIN integrations i ON i.id=c.integration_id
                  WHERE m.id=? AND m.case_id=? AND c.integration_id=? AND i.provider='TEAMS'
                    AND NOT m.inbound AND m.kind='SUPPORT' AND c.external_conversation_id=?
                    AND m.external_thread_key IS NOT DISTINCT FROM ?)
                """,Boolean.class,command.messageId(),command.caseId(),command.integrationId(),command.externalConversationId(),command.externalThreadKey()));
    }
    void record(DeliveryCommand command) {
        int count=jdbc.update("""
                INSERT INTO teams_outbound_acceptance(message_id,http_status)
                SELECT m.id,202 FROM messages m JOIN cases c ON c.id=m.case_id
                  JOIN integrations i ON i.id=c.integration_id
                WHERE m.id=? AND m.case_id=? AND c.integration_id=? AND i.provider='TEAMS'
                  AND NOT m.inbound AND m.kind='SUPPORT' AND m.delivery_status='SENDING'
                  AND c.external_conversation_id=? AND m.external_thread_key IS NOT DISTINCT FROM ?
                ON CONFLICT(message_id) DO NOTHING
                """,command.messageId(),command.caseId(),command.integrationId(),command.externalConversationId(),command.externalThreadKey());
        if(count!=1&&!accepted(command))throw new IllegalStateException("Teams acceptance context was not found.");
    }
}
