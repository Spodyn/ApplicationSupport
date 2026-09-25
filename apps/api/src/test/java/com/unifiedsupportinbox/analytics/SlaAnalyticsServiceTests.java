package com.unifiedsupportinbox.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SlaAnalyticsServiceTests {

    @Test
    void computesOutcomeBreakdownAndNearestRankDurationPercentiles() {
        SlaAnalyticsService.Breakdown breakdown = SlaAnalyticsService.Breakdown.from(
                SlaAnalyticsService.Type.UNCLAIMED,
                List.of(
                        new SlaAnalyticsService.Sample(SlaAnalyticsService.Type.UNCLAIMED, SlaAnalyticsService.Outcome.ACHIEVED, 10),
                        new SlaAnalyticsService.Sample(SlaAnalyticsService.Type.UNCLAIMED, SlaAnalyticsService.Outcome.WARNING, 20),
                        new SlaAnalyticsService.Sample(SlaAnalyticsService.Type.UNCLAIMED, SlaAnalyticsService.Outcome.BREACHED, 50)));

        assertThat(breakdown.completed()).isEqualTo(3);
        assertThat(breakdown.achieved()).isEqualTo(1);
        assertThat(breakdown.warnings()).isEqualTo(1);
        assertThat(breakdown.breaches()).isEqualTo(1);
        assertThat(breakdown.attainmentPercent()).isEqualTo(33);
        assertThat(breakdown.durationSeconds()).isEqualTo(new SlaAnalyticsService.Distribution(26L, 20L, 50L));
    }

    @Test
    void representsNoCompletedPolicySnapshotsWithoutInventingAnAttainmentRate() {
        SlaAnalyticsService.Breakdown breakdown = SlaAnalyticsService.Breakdown.from(
                SlaAnalyticsService.Type.FIRST_RESPONSE, List.of());

        assertThat(breakdown.attainmentPercent()).isNull();
        assertThat(breakdown.durationSeconds()).isEqualTo(new SlaAnalyticsService.Distribution(null, null, null));
    }
}
