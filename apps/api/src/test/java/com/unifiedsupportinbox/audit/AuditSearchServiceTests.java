package com.unifiedsupportinbox.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.unifiedsupportinbox.CursorCodec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditSearchServiceTests {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void filtersAndPaginatesSanitizedAuditItems() {
        AuditSearchRepository repository = mock(AuditSearchRepository.class);
        UUID actor = UUID.randomUUID();
        AuditSearchService.Filters filters = new AuditSearchService.Filters(
                NOW.minusSeconds(3600), NOW, actor, "CASE_CLAIM", "CASE", null, null, "request-audit-test");
        AuditSearchService.AuditEventItem first = event(NOW, actor, "{\"outcome\":\"accepted\"}");
        AuditSearchService.AuditEventItem second = event(NOW.minusSeconds(1), actor, "{\"outcome\":\"accepted\"}");
        when(repository.find(eq(filters), any(), any(), eq(2))).thenReturn(List.of(first, second));
        AuditSearchService service = new AuditSearchService(repository,
                new CursorCodec("audit-search-test-key-material-123".getBytes(StandardCharsets.UTF_8),
                        Clock.fixed(NOW, ZoneOffset.UTC)));

        var page = service.search(filters, null, 1);

        assertThat(page.items()).containsExactly(first);
        assertThat(page.nextCursor()).isNotBlank();
        assertThat(page.items().getFirst().metadataJson()).doesNotContain("password").doesNotContain("messageBody");
        verify(repository).find(eq(filters), eq(null), eq(null), eq(2));
    }

    private static AuditSearchService.AuditEventItem event(Instant occurredAt, UUID actor, String metadata) {
        return new AuditSearchService.AuditEventItem(
                UUID.randomUUID(), AuditActorType.USER, actor, "CASE_CLAIM", "CASE", UUID.randomUUID(),
                UUID.randomUUID(), "request-audit-test", occurredAt, metadata);
    }
}
