package com.unifiedsupportinbox.messaging.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

interface MessageRepository extends JpaRepository<MessageEntity, UUID> {

    Optional<MessageEntity> findByCaseIdAndExternalMessageId(UUID caseId, String externalMessageId);

    List<MessageEntity> findByCaseIdOrderByProviderCreatedAtAscIdAsc(UUID caseId);

    @Query(value = """
            SELECT * FROM messages
            WHERE case_id = :caseId
              AND (:beforeAt IS NULL
                   OR COALESCE(provider_created_at, created_at) < :beforeAt
                   OR (COALESCE(provider_created_at, created_at) = :beforeAt AND id < :beforeId))
            ORDER BY COALESCE(provider_created_at, created_at) DESC, id DESC
            """, nativeQuery = true)
    List<MessageEntity> findHistoryPage(
            @Param("caseId") UUID caseId,
            @Param("beforeAt") Instant beforeAt,
            @Param("beforeId") UUID beforeId,
            Pageable pageable);
}
