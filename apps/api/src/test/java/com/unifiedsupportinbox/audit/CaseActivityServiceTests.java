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
import tools.jackson.databind.json.JsonMapper;

class CaseActivityServiceTests {
    private static final UUID VIEWER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void pagesStableTimestampOrderAndRedactsMetadataForEveryActorType() {
        CaseActivityRepository repository = mock(CaseActivityRepository.class);
        UUID caseId = UUID.randomUUID();
        when(repository.caseExists(caseId)).thenReturn(true);
        List<CaseActivityService.RawActivity> events = List.of(
                event(AuditActorType.USER, "Agent", "CASE_CLAIM", NOW, "{\"outcome\":\"accepted\",\"token\":\"remove\"}"),
                event(AuditActorType.ADMIN, "Admin", "CASE_REASSIGN", NOW, "{\"reason\":\"coverage\"}"),
                event(AuditActorType.SYSTEM, null, "CASE_WAITING_TIMEOUT", NOW.minusSeconds(1), "{}"),
                event(AuditActorType.PROVIDER, null, "CASE_PROVIDER_MESSAGE", NOW.minusSeconds(2), "{\"body\":\"remove\"}"));
        when(repository.find(eq(caseId), eq(VIEWER), any(), any(), eq(3))).thenReturn(events.subList(0, 3));
        CaseActivityService service = service(repository);

        var page = service.activity(caseId, VIEWER, null, 2);

        assertThat(page.items()).extracting(CaseActivityService.ActivityItem::actorLabel)
                .containsExactly("Agent", "Admin");
        assertThat(page.items().getFirst().actionDescription()).isEqualTo("Case claimed");
        assertThat(page.items().getFirst().metadata().toString()).doesNotContain("token");
        assertThat(page.nextCursor()).isNotBlank();
        verify(repository).find(eq(caseId), eq(VIEWER), eq(null), eq(null), eq(3));
    }

    @Test
    void providesReadableFallbackLabelsForSystemAndProviderActors() {
        CaseActivityRepository repository = mock(CaseActivityRepository.class);
        UUID caseId = UUID.randomUUID();
        when(repository.caseExists(caseId)).thenReturn(true);
        when(repository.find(eq(caseId), eq(VIEWER), any(), any(), eq(101))).thenReturn(List.of(
                event(AuditActorType.SYSTEM, null, "CASE_WAITING_TIMEOUT", NOW, "{}"),
                event(AuditActorType.PROVIDER, null, "CASE_CUSTOMER_REPLY", NOW.minusSeconds(1), "{}")));

        var page = service(repository).activity(caseId, VIEWER, null, 100);

        assertThat(page.items()).extracting(CaseActivityService.ActivityItem::actorLabel)
                .containsExactly("System", "Customer");
        assertThat(page.items().getLast().actionDescription()).isEqualTo("Customer replied");
        assertThat(page.nextCursor()).isNull();
    }

    @Test void messageMutationOutcomesHaveStableDescriptionsWithoutExposingContent() {
        var repository=mock(CaseActivityRepository.class);UUID caseId=UUID.randomUUID();
        when(repository.caseExists(caseId)).thenReturn(true);
        when(repository.find(eq(caseId),eq(VIEWER),any(),any(),eq(11))).thenReturn(List.of(
                event(AuditActorType.USER,"Author","MESSAGE_MUTATION_REQUESTED",NOW,"{\"body\":\"private draft\"}"),
                event(AuditActorType.USER,"Author","SUPPORT_MESSAGE_EDITED",NOW,"{}"),
                event(AuditActorType.PROVIDER,null,"SUPPORT_MESSAGE_DELETED",NOW,"{}"),
                event(AuditActorType.USER,"Author","MESSAGE_MUTATION_UNCERTAIN",NOW,"{}")));
        var items=service(repository).activity(caseId,VIEWER,null,10).items();
        assertThat(items).extracting(CaseActivityService.ActivityItem::actionDescription).containsExactly(
                "Message mutation requested","Support message edited","Support message deleted","Message mutation awaiting confirmation");
        assertThat(items.getFirst().metadata().toString()).doesNotContain("private draft");
    }

    private static CaseActivityService service(CaseActivityRepository repository) {
        return new CaseActivityService(repository,
                new CursorCodec("case-activity-test-key-material-123".getBytes(StandardCharsets.UTF_8),
                        Clock.fixed(NOW, ZoneOffset.UTC)),
                JsonMapper.builder().build());
    }

    private static CaseActivityService.RawActivity event(
            AuditActorType actorType, String displayName, String action, Instant occurredAt, String metadata) {
        return new CaseActivityService.RawActivity(UUID.randomUUID(), actorType, UUID.randomUUID(), null,
                displayName, action, occurredAt, metadata, null, null);
    }
}
