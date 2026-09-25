package com.unifiedsupportinbox.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class StatisticsOverviewServiceTests {

    @Test
    void totalsKeepNoDataDurationsNullAndCalculateWeightedAverages() {
        StatisticsOverviewService.Totals empty = StatisticsOverviewService.Totals.from(List.of());
        assertThat(empty.averageFirstResponseSeconds()).isNull();
        assertThat(empty.averageResolutionSeconds()).isNull();

        StatisticsOverviewService.Totals totals = StatisticsOverviewService.Totals.from(List.of(
                new StatisticsOverviewService.Day(LocalDate.of(2026, 9, 1), 2, 1, 2, 60, 1, 0, 100),
                new StatisticsOverviewService.Day(LocalDate.of(2026, 9, 2), 3, 2, 1, 90, 2, 1, 500)));

        assertThat(totals.created()).isEqualTo(5);
        assertThat(totals.claimed()).isEqualTo(3);
        assertThat(totals.firstResponses()).isEqualTo(3);
        assertThat(totals.resolved()).isEqualTo(3);
        assertThat(totals.ignored()).isEqualTo(1);
        assertThat(totals.averageFirstResponseSeconds()).isEqualTo(50);
        assertThat(totals.averageResolutionSeconds()).isEqualTo(200);
    }
}
