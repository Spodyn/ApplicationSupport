package com.unifiedsupportinbox.provider.slack.internal;

import java.util.Locale;
import java.util.Set;

final class SlackDeliveryErrorClassifier {

    private static final Set<String> TRANSIENT_API_ERRORS = Set.of(
            "fatal_error",
            "internal_error",
            "org_login_required",
            "rate_limited",
            "ratelimited",
            "request_timeout",
            "service_unavailable",
            "team_added_to_org");

    private SlackDeliveryErrorClassifier() {
    }

    static boolean isTransientApiError(String errorCode) {
        return errorCode != null && TRANSIENT_API_ERRORS.contains(errorCode);
    }

    static boolean isTransientHttpStatus(int statusCode) {
        return statusCode == 408 || statusCode == 429 || statusCode >= 500;
    }

    static String normalizedApiError(String errorCode) {
        if (errorCode == null || errorCode.isBlank()) return "SLACK_API_ERROR";
        String normalized = errorCode.toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9_]+", "_");
        String code = "SLACK_" + normalized;
        return code.length() <= 128 ? code : code.substring(0, 128);
    }
}
