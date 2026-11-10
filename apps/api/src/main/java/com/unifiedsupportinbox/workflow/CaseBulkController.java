package com.unifiedsupportinbox.workflow;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.ApiV1Conventions;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/cases/bulk")
class CaseBulkController {
    private final CaseBulkService bulk;
    CaseBulkController(CaseBulkService bulk) { this.bulk = bulk; }

    @PostMapping
    JsonNode execute(@RequestHeader(ApiV1Conventions.IDEMPOTENCY_KEY_HEADER) String key,
            @RequestBody CaseBulkService.Request body, Authentication authentication, HttpServletRequest request) {
        if (authentication == null || !authentication.isAuthenticated()) throw ApiProblemException.authenticationRequired();
        UUID actor;
        try { actor = UUID.fromString(authentication.getName()); }
        catch (IllegalArgumentException error) { throw ApiProblemException.authenticationRequired(); }
        Object correlation = request.getAttribute("usi.correlationId");
        return bulk.execute(actor, key, body, correlation instanceof String id ? id : UUID.randomUUID().toString());
    }
}
