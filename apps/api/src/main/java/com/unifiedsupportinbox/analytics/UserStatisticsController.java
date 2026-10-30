package com.unifiedsupportinbox.analytics;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Own metrics are available to an authenticated user; other users require ADMIN + global view. */
@RestController
@RequestMapping("/api/v1/statistics/users")
class UserStatisticsController {

    private final UserStatisticsService statistics;

    UserStatisticsController(UserStatisticsService statistics) {
        this.statistics = statistics;
    }

    @GetMapping("/{userId}")
    UserStatisticsService.Report user(
            @PathVariable UUID userId,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) UUID customerId,
            Authentication authentication) {
        return statistics.report(authentication, userId, from, to, provider, customerId);
    }
}
