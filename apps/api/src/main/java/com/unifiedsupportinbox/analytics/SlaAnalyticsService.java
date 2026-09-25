package com.unifiedsupportinbox.analytics;

import com.unifiedsupportinbox.ApiProblemException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SlaAnalyticsService {
    private static final int DEFAULT_RANGE_DAYS = 30;
    private static final int MAX_RANGE_DAYS = 366;
    private final SlaAnalyticsRepository samples;
    private final StatisticsOverviewRepository statistics;

    SlaAnalyticsService(SlaAnalyticsRepository samples, StatisticsOverviewRepository statistics) {
        this.samples = samples;
        this.statistics = statistics;
    }

    @Transactional(readOnly = true)
    Report report(LocalDate requestedFrom, LocalDate requestedTo, String provider, UUID customerId, UUID userId) {
        String timezone = statistics.activeTimezone();
        LocalDate to = requestedTo == null ? LocalDate.now(ZoneId.of(timezone)) : requestedTo;
        LocalDate from = requestedFrom == null ? to.minusDays(DEFAULT_RANGE_DAYS - 1L) : requestedFrom;
        if (from.isAfter(to) || from.plusDays(MAX_RANGE_DAYS - 1L).isBefore(to)) {
            throw ApiProblemException.validationFailed("Date range must be ordered and contain at most 366 reporting days.");
        }
        String normalizedProvider = normalizeProvider(provider);
        List<Breakdown> breakdowns = new ArrayList<>();
        for (Type type : Type.values()) {
            List<Sample> typeSamples = samples.completed(from, to, timezone, normalizedProvider, customerId, userId).stream()
                    .filter(sample -> sample.type == type).toList();
            breakdowns.add(Breakdown.from(type, typeSamples));
        }
        return new Report(from, to, timezone, normalizedProvider, customerId, userId, breakdowns);
    }

    private static String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank()) return null;
        String normalized = provider.strip().toUpperCase(java.util.Locale.ROOT);
        if (!List.of("SLACK", "TEAMS", "TELEGRAM").contains(normalized)) {
            throw ApiProblemException.validationFailed("provider must be SLACK, TEAMS, or TELEGRAM.");
        }
        return normalized;
    }

    enum Type { FIRST_RESPONSE, UNCLAIMED }
    enum Outcome { ACHIEVED, WARNING, BREACHED }
    record Sample(Type type, Outcome outcome, long durationSeconds) { }
    record Report(LocalDate from, LocalDate to, String timezone, String provider, UUID customerId, UUID userId,
                  List<Breakdown> breakdowns) { }
    record Breakdown(Type type, long completed, long achieved, long warnings, long breaches,
                     Long attainmentPercent, Distribution durationSeconds) {
        static Breakdown from(Type type, List<Sample> samples) {
            long achieved = samples.stream().filter(s -> s.outcome == Outcome.ACHIEVED).count();
            long warnings = samples.stream().filter(s -> s.outcome == Outcome.WARNING).count();
            long breaches = samples.stream().filter(s -> s.outcome == Outcome.BREACHED).count();
            long completed = samples.size();
            return new Breakdown(type, completed, achieved, warnings, breaches,
                    completed == 0 ? null : achieved * 100 / completed, Distribution.from(samples));
        }
    }
    record Distribution(Long average, Long p50, Long p95) {
        static Distribution from(List<Sample> samples) {
            if (samples.isEmpty()) return new Distribution(null, null, null);
            List<Long> values = samples.stream().map(Sample::durationSeconds).sorted(Comparator.naturalOrder()).toList();
            long sum = values.stream().mapToLong(Long::longValue).sum();
            return new Distribution(sum / values.size(), percentile(values, 50), percentile(values, 95));
        }
        private static long percentile(List<Long> values, int percentile) {
            return values.get((int) Math.ceil(percentile / 100.0 * values.size()) - 1);
        }
    }
}
