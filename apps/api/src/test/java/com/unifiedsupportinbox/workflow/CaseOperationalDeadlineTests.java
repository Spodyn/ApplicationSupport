package com.unifiedsupportinbox.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.unifiedsupportinbox.sla.BusinessHoursScheduleView;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class CaseOperationalDeadlineTests {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @ParameterizedTest
    @ValueSource(strings = {"FRT", "NRT"})
    void expiredSupportDeadlineIncludesClosedWeekendInOverdueDuration(String kind) throws Exception {
        var due = Instant.parse("2026-03-27T16:00:00Z");
        var observed = Instant.parse("2026-03-29T10:00:00Z");
        var result = CaseOperationalDeadline.read(row(kind, due, observed), mapper);
        assertThat(result.remainingMillis()).isEqualTo(-42 * 3_600_000L);
        assertThat(result.dueAt()).isEqualTo(due);
        assertThat(result.observedAt()).isEqualTo(observed);
        assertThat(result.businessIntervals()).singleElement().satisfies(interval ->
                assertThat(interval.start()).isEqualTo(Instant.parse("2026-03-30T07:00:00Z")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"FRT", "NRT"})
    void unexpiredSupportDeadlineExcludesWeekendAndHonorsWarsawDst(String kind) throws Exception {
        var due = Instant.parse("2026-03-30T07:15:00Z");
        var observed = Instant.parse("2026-03-29T10:00:00Z");
        var result = CaseOperationalDeadline.read(row(kind, due, observed), mapper);
        assertThat(result.remainingMillis()).isEqualTo(15 * 60_000L);
        assertThat(result.businessIntervals()).singleElement().satisfies(interval -> {
            assertThat(interval.start()).isEqualTo(Instant.parse("2026-03-30T07:00:00Z"));
            assertThat(interval.end()).isEqualTo(Instant.parse("2026-03-30T10:00:00Z"));
        });
    }

    private ResultSet row(String kind, Instant due, Instant observed) throws Exception {
        var schedule = new BusinessHoursScheduleView(UUID.randomUUID(), "Europe/Warsaw", true,
                List.of(new BusinessHoursScheduleView.Interval(1, "09:00", "17:00"),
                        new BusinessHoursScheduleView.Interval(5, "09:00", "17:00")),
                List.of(), "test", Instant.EPOCH);
        var rs = mock(ResultSet.class);
        when(rs.getObject("operational_id", UUID.class)).thenReturn(UUID.randomUUID());
        when(rs.getObject("operational_due_at", OffsetDateTime.class)).thenReturn(due.atOffset(ZoneOffset.UTC));
        when(rs.getObject("response_observed_at", OffsetDateTime.class)).thenReturn(observed.atOffset(ZoneOffset.UTC));
        when(rs.getString("operational_kind")).thenReturn(kind);
        when(rs.getString("operational_schedule")).thenReturn(mapper.writeValueAsString(schedule));
        return rs;
    }
}
