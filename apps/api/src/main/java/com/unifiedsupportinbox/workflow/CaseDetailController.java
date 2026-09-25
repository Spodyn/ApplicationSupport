package com.unifiedsupportinbox.workflow;

import com.unifiedsupportinbox.ApiProblemException;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cases")
class CaseDetailController {
    private final CaseDetailService details;

    CaseDetailController(CaseDetailService details) { this.details = details; }

    @GetMapping("/{caseId}")
    CaseDetailService.CaseDetail detail(@PathVariable UUID caseId, Authentication authentication) {
        return details.detail(caseId, authenticatedUserId(authentication));
    }

    private static UUID authenticatedUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) throw ApiProblemException.authenticationRequired();
        try { return UUID.fromString(authentication.getName()); }
        catch (IllegalArgumentException exception) { throw ApiProblemException.authenticationRequired(); }
    }
}
