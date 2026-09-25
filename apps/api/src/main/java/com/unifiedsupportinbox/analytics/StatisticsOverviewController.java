package com.unifiedsupportinbox.analytics;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/statistics")
class StatisticsOverviewController {

    private final StatisticsOverviewService statistics;

    StatisticsOverviewController(StatisticsOverviewService statistics) {
        this.statistics = statistics;
    }

    @GetMapping("/overview")
    StatisticsOverviewService.Overview overview(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) UUID customerId) {
        return statistics.overview(from, to, provider, customerId);
    }
}
