package com.unifiedsupportinbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ApiV1ConventionsTests {

    @Test
    void freezesApiV1PathQueryNamesPageSizesAndUtcTimestampFormat() {
        assertThat(ApiV1Conventions.BASE_PATH).isEqualTo("/api/v1");
        assertThat(ApiV1Conventions.CURSOR_QUERY_PARAMETER).isEqualTo("cursor");
        assertThat(ApiV1Conventions.LIMIT_QUERY_PARAMETER).isEqualTo("limit");
        assertThat(ApiV1Conventions.SORT_QUERY_PARAMETER).isEqualTo("sort");
        assertThat(ApiV1Conventions.pageSize(null)).isEqualTo(50);
        assertThat(ApiV1Conventions.pageSize(100)).isEqualTo(100);
        assertThatThrownBy(() -> ApiV1Conventions.pageSize(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApiV1Conventions.pageSize(101)).isInstanceOf(IllegalArgumentException.class);

        Instant timestamp = Instant.parse("2026-08-28T12:34:56.123456Z");
        assertThat(ApiV1Conventions.formatUtcTimestamp(timestamp))
                .isEqualTo("2026-08-28T12:34:56.123456Z");
        assertThat(ApiV1Conventions.parseUtcTimestamp("2026-08-28T12:34:56.123456Z"))
                .isEqualTo(timestamp);
        assertThatThrownBy(() -> ApiV1Conventions.parseUtcTimestamp("2026-08-28T14:34:56+02:00"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresCanonicalLowerCaseUuidText() {
        UUID uuid = UUID.fromString("018f7777-1111-7aaa-8bbb-ccccdddd0001");
        assertThat(ApiV1Conventions.parseUuid(uuid.toString())).isEqualTo(uuid);
        assertThatThrownBy(() -> ApiV1Conventions.parseUuid(uuid.toString().toUpperCase()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApiV1Conventions.parseUuid("018f7777-1111-7aaa-8bbb-ccccdddd1"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
