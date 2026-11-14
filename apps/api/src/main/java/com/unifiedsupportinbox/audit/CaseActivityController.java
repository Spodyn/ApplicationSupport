package com.unifiedsupportinbox.audit;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.CursorPage;
import org.springframework.security.core.Authentication;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cases")
class CaseActivityController {
    private final CaseActivityService activity;

    CaseActivityController(CaseActivityService activity) { this.activity = activity; }

    @GetMapping("/{caseId}/activity")
    CursorPage<CaseActivityService.ActivityItem> activity(
            @PathVariable UUID caseId,
            Authentication authentication,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(100) Integer limit) {
        if (authentication == null || !authentication.isAuthenticated()) throw ApiProblemException.authenticationRequired();
        UUID viewer;
        try {
            viewer = UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException invalid) {
            throw ApiProblemException.authenticationRequired();
        }
        return activity.activity(caseId, viewer, cursor, limit);
    }
}
