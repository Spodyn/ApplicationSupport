package com.unifiedsupportinbox.cases.internal;

import com.unifiedsupportinbox.integration.IntegrationProvider;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CaseRepository extends JpaRepository<CaseEntity, UUID> {

    Optional<CaseEntity> findByReference(String reference);

    @Query(value = """
            SELECT *
            FROM cases c
            WHERE c.integration_id = :integrationId
              AND c.channel_id = :channelId
              AND c.provider = CAST(:provider AS varchar)
              AND c.external_conversation_id = :externalConversationId
              AND COALESCE(c.external_thread_key, '') = COALESCE(:externalThreadKey, '')
              AND c.status NOT IN ('IGNORED', 'RESOLVED')
            """, nativeQuery = true)
    Optional<CaseEntity> findActiveByProviderContext(
            @Param("integrationId") UUID integrationId,
            @Param("channelId") UUID channelId,
            @Param("provider") String provider,
            @Param("externalConversationId") String externalConversationId,
            @Param("externalThreadKey") String externalThreadKey);

    default Optional<CaseEntity> findActiveByProviderContext(
            UUID integrationId,
            UUID channelId,
            IntegrationProvider provider,
            String externalConversationId,
            String externalThreadKey) {
        return findActiveByProviderContext(
                integrationId,
                channelId,
                provider.name(),
                externalConversationId,
                externalThreadKey);
    }
}
