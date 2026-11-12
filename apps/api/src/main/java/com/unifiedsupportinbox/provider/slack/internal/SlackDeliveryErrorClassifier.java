package com.unifiedsupportinbox.provider.slack.internal;

import com.unifiedsupportinbox.messaging.MessageDeliveryProvider.DeliveryResult;
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

    private static final Set<String> AUTH_ERRORS = Set.of(
            "account_inactive", "invalid_auth", "missing_scope", "not_allowed_token_type",
            "not_authed", "team_access_not_granted", "token_expired", "token_revoked");
    private static final Set<String> RESTRICTIONS = Set.of(
            "channel_not_found", "is_archived", "not_in_channel", "no_permission",
            "restricted_action", "restricted_action_non_threadable_channel", "restricted_action_read_only_channel",
            "restricted_action_thread_locked", "restricted_action_thread_only_channel",
            "posting_to_channel_denied", "ekm_access_denied", "msg_too_long", "no_text",
            "invalid_blocks", "invalid_arguments", "slack_connect_file_link_sharing_blocked");

    static String safePostError(String error) {
        return error != null && (TRANSIENT_API_ERRORS.contains(error) || AUTH_ERRORS.contains(error)
                || RESTRICTIONS.contains(error)) ? error : null;
    }

    static boolean isIntegrationFailure(String code) {
        return code != null && (code.equals("SLACK_HTTP_401")
                || code.equals("SLACK_BOT_TOKEN_MISSING") || code.equals("SLACK_CREDENTIAL_REFERENCE_MISSING")
                || AUTH_ERRORS.stream().anyMatch(error -> normalizedApiError(error).equals(code)));
    }

    static DeliveryResult classifyPost(SlackWebApiClient.PostMessageResponse response) {
        if (response == null) return DeliveryResult.transientFailure("SLACK_INVALID_RESPONSE", null);

        int status = response.statusCode();
        String error = safePostError(response.errorCode());
        if (status == 429 || "rate_limited".equals(error) || "ratelimited".equals(error)) {
            return DeliveryResult.transientFailure("SLACK_RATE_LIMITED", response.retryAfter());
        }
        if (isTransientHttpStatus(status)) {
            return DeliveryResult.transientFailure("SLACK_HTTP_" + status, response.retryAfter());
        }
        if (error != null) {
            String code = normalizedApiError(error);
            return isTransientApiError(error)
                    ? DeliveryResult.transientFailure(code, response.retryAfter())
                    : DeliveryResult.permanentFailure(code);
        }
        if (status < 200 || status >= 300) return DeliveryResult.permanentFailure("SLACK_HTTP_" + status);
        if (!response.ok()) return DeliveryResult.transientFailure("SLACK_INVALID_RESPONSE", response.retryAfter());
        if (response.messageTs() == null || response.messageTs().isBlank()) {
            return DeliveryResult.transientFailure("SLACK_RESPONSE_TS_MISSING", null);
        }
        return DeliveryResult.sent(response.messageTs());
    }

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
