package com.unifiedsupportinbox.workflow;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.ApiV1Conventions;
import com.unifiedsupportinbox.CursorPage;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cases")
class CaseListController {
    private final CaseListService cases;

    CaseListController(CaseListService cases) {
        this.cases = cases;
    }

    @GetMapping
    CursorPage<CaseListService.CaseListItem> list(
            @RequestParam(name = ApiV1Conventions.CURSOR_QUERY_PARAMETER, required = false) String cursor,
            @RequestParam(name = ApiV1Conventions.LIMIT_QUERY_PARAMETER, required = false) Integer limit,
            Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw ApiProblemException.authenticationRequired();
        }
        UUID userId;
        try {
            userId = UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException exception) {
            throw ApiProblemException.authenticationRequired();
        }
        return cases.list(userId, cursor, limit);
    }
}
