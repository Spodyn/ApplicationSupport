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
    private final SlaAnalyticsService sla;

    StatisticsOverviewController(StatisticsOverviewService statistics, SlaAnalyticsService sla) {
        this.statistics = statistics;
        this.sla = sla;
    }

    @GetMapping("/overview")
    StatisticsOverviewService.Overview overview(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) UUID customerId) {
        return statistics.overview(from, to, provider, customerId);
    }

    @GetMapping("/sla")
    SlaAnalyticsService.Report sla(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) UUID userId) {
        return sla.report(from, to, provider, customerId, userId);
    }
}
