package com.unifiedsupportinbox.messaging.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface MessageRepository extends JpaRepository<MessageEntity, UUID> {

    Optional<MessageEntity> findByCaseIdAndExternalMessageId(UUID caseId, String externalMessageId);

    List<MessageEntity> findByCaseIdOrderByProviderCreatedAtAscIdAsc(UUID caseId);
}
