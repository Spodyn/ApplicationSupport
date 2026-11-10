package com.unifiedsupportinbox.workflow;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.ApiV1Conventions;
import com.unifiedsupportinbox.CursorPage;
import com.unifiedsupportinbox.cases.CaseStatus;
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
            @RequestParam(name = ApiV1Conventions.LIMIT_QUERY_PARAMETER, required = false) @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(100) Integer limit,
            @RequestParam(name = "view", required = false) CaseListView view,
            @RequestParam(name = "mine", defaultValue = "false") boolean mine,
            @RequestParam(name = "unassigned", defaultValue = "false") boolean unassigned,
            @RequestParam(name = "unread", defaultValue = "false") boolean unread,
            @RequestParam(name = "slaAttention", defaultValue = "false") boolean slaAttention,
            @RequestParam(name = "status", required = false) CaseStatus status,
            @RequestParam(name = "statusScope", required = false) CaseListFilter.StatusScope statusScope,
            @RequestParam(name = "customerId", required = false) UUID customerId,
            @RequestParam(name = "channelId", required = false) UUID channelId,
            @RequestParam(name = "provider", required = false) String provider,
            @RequestParam(name = "ownerId", required = false) UUID ownerId,
            @RequestParam(name = "search", required = false) String search,
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
        return cases.list(userId, cursor, limit, view,
                new CaseListFilter(mine, unassigned, unread, slaAttention, status, statusScope,
                        customerId, channelId, provider, ownerId, search));
    }
}
