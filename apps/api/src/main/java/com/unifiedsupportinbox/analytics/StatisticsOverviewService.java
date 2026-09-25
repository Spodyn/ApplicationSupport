package com.unifiedsupportinbox.analytics;

import com.unifiedsupportinbox.ApiProblemException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class StatisticsOverviewService {

    private static final int DEFAULT_RANGE_DAYS = 30;
    private static final int MAX_RANGE_DAYS = 366;

    private final StatisticsOverviewRepository aggregates;

    StatisticsOverviewService(StatisticsOverviewRepository aggregates) {
        this.aggregates = aggregates;
    }

    @Transactional(readOnly = true)
    Overview overview(LocalDate requestedFrom, LocalDate requestedTo, String provider, UUID customerId) {
        String timezone = aggregates.activeTimezone();
        LocalDate today = LocalDate.now(ZoneId.of(timezone));
        LocalDate to = requestedTo == null ? today : requestedTo;
        LocalDate from = requestedFrom == null ? to.minusDays(DEFAULT_RANGE_DAYS - 1L) : requestedFrom;
        if (from.isAfter(to) || from.plusDays(MAX_RANGE_DAYS - 1L).isBefore(to)) {
            throw ApiProblemException.validationFailed("Date range must be ordered and contain at most 366 reporting days.");
        }
        String normalizedProvider = normalizeProvider(provider);
        List<Day> series = aggregates.find(from, to, timezone, normalizedProvider, customerId);
        Totals totals = Totals.from(series);
        return new Overview(from, to, timezone, normalizedProvider, customerId, totals, series);
    }

    private static String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank()) return null;
        String normalized = provider.strip().toUpperCase(java.util.Locale.ROOT);
        if (!List.of("SLACK", "TEAMS", "TELEGRAM").contains(normalized)) {
            throw ApiProblemException.validationFailed("provider must be SLACK, TEAMS, or TELEGRAM.");
        }
        return normalized;
    }

    record Overview(LocalDate from, LocalDate to, String timezone, String provider, UUID customerId,
                    Totals totals, List<Day> series) {
    }

    record Day(LocalDate date, long created, long claimed, long firstResponses,
               long firstResponseDurationSeconds, long resolved, long ignored, long resolutionDurationSeconds) {
    }

    record Totals(long created, long claimed, long firstResponses, long resolved, long ignored,
                  Long averageFirstResponseSeconds, Long averageResolutionSeconds) {
        static Totals from(List<Day> days) {
            long created = days.stream().mapToLong(Day::created).sum();
            long claimed = days.stream().mapToLong(Day::claimed).sum();
            long responses = days.stream().mapToLong(Day::firstResponses).sum();
            long responseSeconds = days.stream().mapToLong(Day::firstResponseDurationSeconds).sum();
            long resolved = days.stream().mapToLong(Day::resolved).sum();
            long ignored = days.stream().mapToLong(Day::ignored).sum();
            long resolutionSeconds = days.stream().mapToLong(Day::resolutionDurationSeconds).sum();
            return new Totals(created, claimed, responses, resolved, ignored,
                    responses == 0 ? null : responseSeconds / responses,
                    resolved == 0 ? null : resolutionSeconds / resolved);
        }
    }
}
