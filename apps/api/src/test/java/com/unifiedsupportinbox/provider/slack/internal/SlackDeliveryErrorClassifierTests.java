package com.unifiedsupportinbox.provider.slack.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SlackDeliveryErrorClassifierTests {

    @Test
    void documentedTemporarySlackErrorsAreRetryable() {
        for (String code : new String[] {
                "fatal_error",
                "internal_error",
                "org_login_required",
                "rate_limited",
                "ratelimited",
                "request_timeout",
                "service_unavailable",
                "team_added_to_org"
        }) {
            assertThat(SlackDeliveryErrorClassifier.isTransientApiError(code))
                    .as(code)
                    .isTrue();
        }
    }

    @Test
    void authorizationConversationAndPolicyErrorsArePermanent() {
        for (String code : new String[] {
                "account_inactive",
                "channel_not_found",
                "invalid_auth",
                "is_archived",
                "missing_scope",
                "no_permission",
                "not_allowed_token_type",
                "not_authed",
                "not_in_channel",
                "restricted_action",
                "restricted_action_non_threadable_channel",
                "restricted_action_read_only_channel",
                "restricted_action_thread_locked",
                "team_access_not_granted",
                "token_expired",
                "token_revoked"
        }) {
            assertThat(SlackDeliveryErrorClassifier.isTransientApiError(code))
                    .as(code)
                    .isFalse();
        }
    }

    @Test
    void transientHttpStatusesCoverTimeoutRateLimitAndServerFailures() {
        assertThat(SlackDeliveryErrorClassifier.isTransientHttpStatus(408)).isTrue();
        assertThat(SlackDeliveryErrorClassifier.isTransientHttpStatus(429)).isTrue();
        assertThat(SlackDeliveryErrorClassifier.isTransientHttpStatus(500)).isTrue();
        assertThat(SlackDeliveryErrorClassifier.isTransientHttpStatus(503)).isTrue();
        assertThat(SlackDeliveryErrorClassifier.isTransientHttpStatus(400)).isFalse();
        assertThat(SlackDeliveryErrorClassifier.isTransientHttpStatus(403)).isFalse();
    }

    @Test
    void normalizesProviderErrorWithoutLeakingArbitraryLengthText() {
        assertThat(SlackDeliveryErrorClassifier.normalizedApiError("team_added_to_org"))
                .isEqualTo("SLACK_TEAM_ADDED_TO_ORG");
        assertThat(SlackDeliveryErrorClassifier.normalizedApiError(null))
                .isEqualTo("SLACK_API_ERROR");
        assertThat(SlackDeliveryErrorClassifier.normalizedApiError("bad error/value"))
                .isEqualTo("SLACK_BAD_ERROR_VALUE");
        assertThat(SlackDeliveryErrorClassifier.normalizedApiError("x".repeat(200)))
                .hasSize(128)
                .startsWith("SLACK_");
    }
}
