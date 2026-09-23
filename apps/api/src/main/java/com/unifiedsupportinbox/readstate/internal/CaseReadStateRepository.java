package com.unifiedsupportinbox.readstate.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface CaseReadStateRepository extends JpaRepository<CaseReadStateEntity, CaseReadStateId> {

    List<CaseReadStateEntity> findByIdUserId(UUID userId);

    List<CaseReadStateEntity> findByIdCaseId(UUID caseId);
}
