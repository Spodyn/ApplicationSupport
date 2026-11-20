package com.unifiedsupportinbox.provider.slack.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.unifiedsupportinbox.integration.*;
import com.unifiedsupportinbox.provider.HistoryRecoveryAdapter.*;
import com.unifiedsupportinbox.provider.internal.ConfiguredProviderSecretResolver;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.ObjectMapper;

class SlackHistoryRecoveryAdapterTests {
    ObjectMapper json=new ObjectMapper();
    SlackHistoryClient client=mock(SlackHistoryClient.class);
    ProviderIntegrationCredentialLookup integrations=mock(ProviderIntegrationCredentialLookup.class);
    ConfiguredProviderSecretResolver secrets=mock(ConfiguredProviderSecretResolver.class);
    SlackHistoryRecoveryAdapter adapter=new SlackHistoryRecoveryAdapter(client,integrations,secrets,mock(SlackInboundDeliveryService.class),json);
    UUID integration=UUID.randomUUID();
    Instant now=Instant.parse("2026-11-20T10:30:00Z");
    @BeforeEach void setup() {
        when(integrations.findEnabledForProvider(IntegrationProvider.SLACK)).thenReturn(List.of(
                new ProviderIntegrationCredentialLookup.CredentialReference(integration,"T-SYNTHETIC","fixture/slack")));
        when(secrets.resolve(anyString(),anyString())).thenAnswer(i->Optional.of("synthetic-history-token".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
    }
    Task task(String thread,Instant from) {return new Task(UUID.randomUUID(),UUID.randomUUID(),integration,UUID.randomUUID(),"C-TEST","SLACK","manual",from,now,thread,null,UUID.randomUUID(),json.createObjectNode());}
    @Test void scansOldRootsToFindNewRepliesAndFiltersBySentTime() {
        Instant from=now.minusSeconds(7200);
        String old=SlackHistoryRecoveryAdapter.ts(from.minusSeconds(86400));
        String recent=SlackHistoryRecoveryAdapter.ts(now.minusSeconds(60));
        when(client.history(any(),eq("C-TEST"),isNull(),isNull(),anyString(),eq(15)))
                .thenReturn(new SlackHistoryClient.Page(200,true,List.of(json.readTree("{\"ts\":\""+old+"\",\"reply_count\":1}"),json.readTree("{\"ts\":\""+recent+"\"}")),"next-page",null,null));
        var roots=adapter.fetch(task("",from));
        assertThat(roots.threads()).containsExactly(old);
        assertThat(roots.messages()).hasSize(1);
        assertThat(roots.nextCursor()).isEqualTo("next-page");
        when(client.replies(any(),eq("C-TEST"),eq(old),isNull(),eq(SlackHistoryRecoveryAdapter.ts(from)),anyString(),eq(15)))
                .thenReturn(new SlackHistoryClient.Page(200,true,List.of(json.readTree("{\"ts\":\""+old+"\"}"),json.readTree("{\"ts\":\""+recent+"\",\"thread_ts\":\""+old+"\"}")),null,null,null));
        assertThat(adapter.fetch(task(old,from)).messages()).hasSize(1);
        // All-history removes the lower bound and keeps every available root.
        assertThat(adapter.fetch(task("",null)).messages()).hasSize(2);
    }
    @Test void outageAndRateLimitsAreRetryableButMissingPermissionsAreVisibleAndPermanent() {
        when(client.history(any(),anyString(),isNull(),isNull(),anyString(),anyInt()))
                .thenReturn(new SlackHistoryClient.Page(503,false,List.of(),null,null,null));
        assertThatThrownBy(()->adapter.fetch(task("",null))).isInstanceOfSatisfying(Unavailable.class,e->assertThat(e.retryAfter).isEqualTo(Duration.ofSeconds(30)));
        when(client.history(any(),anyString(),isNull(),isNull(),anyString(),anyInt()))
                .thenReturn(new SlackHistoryClient.Page(429,false,List.of(),null,null,Duration.ofMinutes(2)));
        assertThatThrownBy(()->adapter.fetch(task("",null))).isInstanceOfSatisfying(Unavailable.class,e->assertThat(e.retryAfter).isEqualTo(Duration.ofMinutes(2)));
        when(client.history(any(),anyString(),isNull(),isNull(),anyString(),anyInt()))
                .thenReturn(new SlackHistoryClient.Page(200,false,List.of(),null,"missing_scope",null));
        assertThatThrownBy(()->adapter.fetch(task("",null))).isInstanceOfSatisfying(Unavailable.class,e->{assertThat(e.retryAfter).isNull();assertThat(e.code).contains("MISSING_SCOPE");});
    }
}
