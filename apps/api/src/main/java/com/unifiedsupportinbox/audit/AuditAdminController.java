package com.unifiedsupportinbox.audit;

import com.unifiedsupportinbox.CursorPage;
import java.time.Instant;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/audit")
class AuditAdminController {

    private final AuditSearchService audit;

    AuditAdminController(AuditSearchService audit) {
        this.audit = audit;
    }

    @GetMapping
    CursorPage<AuditSearchService.AuditEventItem> search(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) UUID entityId,
            @RequestParam(required = false) UUID caseId,
            @RequestParam(required = false) String correlationId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return audit.search(new AuditSearchService.Filters(
                from, to, actorId, normalize(action), normalize(entityType), entityId, caseId, normalize(correlationId)),
                cursor, limit);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
