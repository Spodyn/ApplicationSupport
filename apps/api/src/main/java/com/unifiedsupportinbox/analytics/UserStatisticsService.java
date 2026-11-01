package com.unifiedsupportinbox.analytics;

import com.unifiedsupportinbox.ApiProblemException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-user historical counters. A user can inspect self; all other-user
 * requests require ADMIN + view_global_statistics by the E16 Jira override.
 * Inactive historical actors remain visible to an authorized administrator.
 */
@Service
class UserStatisticsService {
    private static final int MAX_RANGE_DAYS = 366;
    private static final int DEFAULT_RANGE_DAYS = 30;
    private final UserStatisticsRepository users;
    private final StatisticsOverviewRepository statistics;
    private final SlaAnalyticsService sla;

    UserStatisticsService(UserStatisticsRepository users, StatisticsOverviewRepository statistics,
            SlaAnalyticsService sla) {
        this.users = users;
        this.statistics = statistics;
        this.sla = sla;
    }

    @Transactional(readOnly = true)
    Report report(
            Authentication authentication, UUID targetUserId,
            LocalDate requestedFrom, LocalDate requestedTo,
            String provider, UUID customerId) {
        authorize(authentication, targetUserId);
        String timezone = statistics.activeTimezone();
        LocalDate to = requestedTo == null ? LocalDate.now(ZoneId.of(timezone)) : requestedTo;
        LocalDate from = requestedFrom == null ? to.minusDays(DEFAULT_RANGE_DAYS - 1L) : requestedFrom;
        if (from.isAfter(to) || from.plusDays(MAX_RANGE_DAYS - 1L).isBefore(to)) {
            throw ApiProblemException.validationFailed(
                    "Date range must be ordered and contain at most 366 reporting days.");
        }
        String normalizedProvider = normalizeProvider(provider);
        Metrics metrics = users.find(targetUserId, from, to, timezone, normalizedProvider, customerId)
                .orElseThrow(() -> ApiProblemException.notFound("Requested user was not found."));
        return new Report(from, to, timezone, normalizedProvider, customerId, metrics,
                sla.report(from, to, normalizedProvider, customerId, targetUserId).breakdowns());
    }

    static void authorize(Authentication authentication, UUID targetUserId) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getName() == null) {
            throw ApiProblemException.authenticationRequired();
        }
        UUID actor;
        try {
            actor = UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException invalid) {
            throw ApiProblemException.authenticationRequired();
        }
        if (actor.equals(targetUserId)) return;
        boolean admin = authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"));
        boolean canViewGlobal = authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("view_global_statistics"));
        if (!admin || !canViewGlobal) throw ApiProblemException.accessDenied();
    }

    private static String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank()) return null;
        String normalized = provider.strip().toUpperCase(Locale.ROOT);
        if (!List.of("SLACK", "TEAMS", "TELEGRAM").contains(normalized)) {
            throw ApiProblemException.validationFailed("provider must be SLACK, TEAMS, or TELEGRAM.");
        }
        return normalized;
    }

    record Report(LocalDate from, LocalDate to, String timezone, String provider,
                  UUID customerId, Metrics metrics,
                  List<SlaAnalyticsService.Breakdown> slaOutcomes) {
    }

    record Metrics(UUID userId, String displayName, boolean active, long claimed,
                   long firstResponses, Long averageFirstResponseSeconds,
                   long resolved, Long averageResolutionSeconds) {
    }
}
