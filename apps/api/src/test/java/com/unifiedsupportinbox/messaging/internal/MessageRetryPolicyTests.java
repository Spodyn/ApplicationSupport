package com.unifiedsupportinbox.messaging.internal;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MessageRetryPolicyTests {
    @ParameterizedTest
    @CsvSource({
            "TRANSIENT,SLACK_RATE_LIMITED,true", "TRANSIENT,SLACK_HTTP_503,true",
            "PERMANENT,INTEGRATION_DISABLED,true", "PERMANENT,SLACK_IS_ARCHIVED,false",
            "PERMANENT,SLACK_INVALID_AUTH,false", "PERMANENT,ATTACHMENT_NOT_CLEAN,false",
            ",INTEGRATION_DISABLED,false", "PERMANENT,,false", ",,false"
    })
    void onlyRepairableDeliveryFailuresCanBeRetried(String category, String code, boolean expected) {
        assertThat(MessageRetryPolicy.repairable(category, code)).isEqualTo(expected);
    }
}
