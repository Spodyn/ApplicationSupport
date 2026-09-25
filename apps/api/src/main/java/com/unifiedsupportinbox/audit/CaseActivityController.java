package com.unifiedsupportinbox.audit;

import com.unifiedsupportinbox.CursorPage;
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
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return activity.activity(caseId, cursor, limit);
    }
}
