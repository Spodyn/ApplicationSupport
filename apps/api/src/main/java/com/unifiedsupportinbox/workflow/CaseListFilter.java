package com.unifiedsupportinbox.workflow;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.cases.CaseStatus;
import java.util.Locale;
import java.util.UUID;

record CaseListFilter(
        boolean mine,
        boolean unassigned,
        boolean unread,
        boolean slaAttention,
        CaseStatus status,
        StatusScope statusScope,
        UUID customerId,
        UUID channelId,
        String provider,
        UUID ownerId,
        String search) {

    enum StatusScope { ALL, ACTIVE, TERMINAL }

    CaseListFilter {
        statusScope = statusScope == null ? StatusScope.ALL : statusScope;
        provider = normalizeProvider(provider);
        search = normalizeSearch(search);
    }

    static CaseListFilter empty() {
        return new CaseListFilter(false, false, false, false, null, StatusScope.ALL,
                null, null, null, null, null);
    }

    String scopeToken() {
        return String.join(":",
                Boolean.toString(mine),
                Boolean.toString(unassigned),
                Boolean.toString(unread),
                Boolean.toString(slaAttention),
                status == null ? "" : status.name(),
                statusScope.name(),
                value(customerId),
                value(channelId),
                provider == null ? "" : provider,
                value(ownerId),
                search == null ? "" : search);
    }

    private static String normalizeProvider(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        if (!java.util.Set.of("SLACK", "TEAMS", "TELEGRAM").contains(normalized)) {
            throw ApiProblemException.validationFailed("Unsupported provider filter.");
        }
        return normalized;
    }

    private static String normalizeSearch(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (normalized.length() > 160) throw ApiProblemException.validationFailed("Search query is too long.");
        return normalized.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String value(UUID value) {
        return value == null ? "" : value.toString();
    }
}
