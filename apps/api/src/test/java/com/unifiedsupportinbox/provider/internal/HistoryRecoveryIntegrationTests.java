package com.unifiedsupportinbox.provider.internal;

import static org.assertj.core.api.Assertions.*;
import com.unifiedsupportinbox.*;
import com.unifiedsupportinbox.integration.IntegrationProvider;
import com.unifiedsupportinbox.messaging.InboundMessageCommandHandler;
import com.unifiedsupportinbox.provider.HistoryRecoveryAdapter;
import com.unifiedsupportinbox.provider.HistoryRecoveryAdapter.*;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.*;

@Tag("integration")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
class HistoryRecoveryIntegrationTests {
    static final org.testcontainers.postgresql.PostgreSQLContainer POSTGRES=TestInfrastructure.postgres();
    @Autowired HistoryRecoveryStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired List<HistoryRecoveryAdapter> adapters;
    @Autowired ApplicationContext context;
    @Autowired InboundEventStore events;
    @Autowired InboundMessageCommandHandler messages;
    UUID integration,channel;String provider;
    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry r) {
        POSTGRES.start();r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);
        r.add("spring.datasource.password",POSTGRES::getPassword);r.add("spring.datasource.driver-class-name",POSTGRES::getDriverClassName);
        r.add("spring.flyway.enabled",()->true);r.add("spring.jpa.hibernate.ddl-auto",()->"validate");
        r.add("usi.outbox.relay-enabled",()->false);r.add("usi.bootstrap-admin.enabled",()->false);
    }
    @AfterAll static void stop(){POSTGRES.stop();}
    @BeforeEach void reset(){TestInfrastructure.resetPostgres(POSTGRES);}
    void fixture(String provider) {
        this.provider=provider;
        integration=jdbc.queryForObject("INSERT INTO integrations(provider,display_name,status,health,secret_ref) VALUES (?,'Synthetic recovery','ENABLED','HEALTHY','synthetic/recovery') RETURNING id",UUID.class,provider);
        UUID customer=jdbc.queryForObject("INSERT INTO customers(name) VALUES ('Recovery customer') RETURNING id",UUID.class);
        channel=jdbc.queryForObject("INSERT INTO channels(integration_id,external_channel_id,name,customer_id,grouping_strategy,active) VALUES (?,'C-RECOVERY','recovery',?,?,TRUE) RETURNING id",UUID.class,integration,customer,provider.equals("SLACK")?"SLACK_ROOT_THREAD":"TEAMS_ROOT_REPLIES");
        jdbc.update("UPDATE channels SET metadata_json='{\"teamsContextType\":\"standardChannel\",\"teamsTeamId\":\"11111111-1111-1111-1111-111111111111\"}'::jsonb WHERE id=?",channel);
        jdbc.update("INSERT INTO teams_conversation_routes(integration_id,external_channel_id,thread_key,connector_conversation_id,service_url,bot_id,context_type) VALUES (?,'C-RECOVERY','','C-RECOVERY','https://smba.trafficmanager.net/emea/','28:synthetic','standardChannel')",integration);
        assertThat(store.view(integration).settings().enabled()).isFalse();
        store.save(integration,new HistoryRecoveryStore.Settings(true,false,30,2880,null,null));
    }
    HistoryRecoveryAdapter adapter(){return adapters.stream().filter(a->a.provider().equals(provider)).findFirst().orElseThrow();}
    String id(Instant at){return provider.equals("SLACK")?at.getEpochSecond()+".000001":Long.toString(at.toEpochMilli());}
    JsonNode payload(String id,Instant sent,String thread) {
        if(provider.equals("SLACK"))return json.readTree("{\"ts\":\""+id+"\",\"thread_ts\":\""+thread+"\",\"user\":\"human\",\"text\":\"recovered\"}");
        return json.readTree("{\"id\":\""+id+"\",\"createdDateTime\":\""+sent+"\",\"from\":{\"user\":{\"id\":\"human\",\"displayName\":\"Customer\"}},\"body\":{\"content\":\"recovered\"}}");
    }
    void processInbound() {
        var worker=context.getBean(provider.equals("SLACK")?"slackInboundWorker":"teamsInboundWorker");
        for(UUID event:jdbc.query("SELECT id FROM inbound_events WHERE resync_task_id IS NOT NULL ORDER BY received_at,id",(rs,n)->rs.getObject(1,UUID.class)))
            ReflectionTestUtils.invokeMethod(worker,"process",event);
    }
    @ParameterizedTest @CsvSource({"SLACK,manual","SLACK,automatic","TEAMS,manual","TEAMS,automatic"})
    void restoredMessagesAreUnreadMarkedAndSlaStartsAtDurableAdditionWithWebhookDedup(String provider,String source) throws Exception {
        fixture(provider);
        jdbc.update("INSERT INTO users(email,display_name,role,active) VALUES ('recovery@example.test','Viewer','USER',TRUE)");
        Instant sent=Instant.now().minusSeconds(7200),now=Instant.now();String root=id(sent.minusSeconds(86400));String message=id(sent);
        UUID run=store.start(integration,sent.minusSeconds(1),source,now);
        Task roots=store.claim().orElseThrow();
        store.commit(roots,adapter(),new Page(List.of(),List.of(root),null));
        Task replies=store.claim().orElseThrow();
        store.commit(replies,adapter(),new Page(List.of(payload(message,sent,root)),List.of(),null));
        processInbound();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM messages",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT recovery_source FROM messages",String.class)).isEqualTo(source);
        assertThat(jdbc.queryForObject("SELECT provider_created_at<created_at-INTERVAL '1 hour' FROM messages",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT s.first_response_started_at=m.created_at FROM case_sla s JOIN messages m ON m.case_id=s.case_id",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM case_read_states",Integer.class)).isEqualTo(1);
        var before=jdbc.queryForMap("SELECT first_response_started_at,first_response_due_at FROM case_sla");
        var event=events.persistAuthenticated(provider,integration,"webhook-duplicate","{}","webhook");
        var duplicate=new InboundMessageCommandHandler.Command(event.id(),integration,channel,IntegrationProvider.valueOf(provider),"webhook-duplicate","C-RECOVERY",message,root,"human","duplicate",InboundMessageCommandHandler.Mutation.CREATE,sent,"webhook");
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->messages.handle(duplicate));var second=executor.submit(()->messages.handle(duplicate));
            assertThat(first.get().created()).isFalse();assertThat(second.get().created()).isFalse();
        }
        assertThat(jdbc.queryForMap("SELECT first_response_started_at,first_response_due_at FROM case_sla")).isEqualTo(before);
        assertThat(store.view(integration).runs().getFirst().id()).isEqualTo(run);
        assertThat(store.view(integration).runs().getFirst().channels().getFirst().added()).isEqualTo(1);
        assertThat(store.view(integration).runs().getFirst().channels().getFirst().status()).isEqualTo("SUCCEEDED");
        // A new customer message after a completed response starts exactly one NRT at insertion time.
        jdbc.update("UPDATE case_sla SET first_response_completed_at=CURRENT_TIMESTAMP,last_response_sender='SUPPORT'");
        assertThat(messages.handle(duplicate).created()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM case_next_response_cycles",Integer.class)).isZero();
        UUID next=store.start(integration,null,source,Instant.now());Task nextRoots=store.claim().orElseThrow();
        store.commit(nextRoots,adapter(),new Page(List.of(),List.of(root),null));
        Task nextReply=store.claim().orElseThrow();String reply=id(sent.plusSeconds(1));
        store.commit(nextReply,adapter(),new Page(List.of(payload(reply,sent.plusSeconds(1),root)),List.of(),null));processInbound();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM case_next_response_cycles",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT n.started_at=m.created_at FROM case_next_response_cycles n JOIN messages m ON m.id=n.opening_message_id",Boolean.class)).isTrue();
    }
    @ParameterizedTest @CsvSource({"SLACK","TEAMS"})
    void normalMessageKeepsItsProvenanceAndSlaAfterRescan(String provider) {
        fixture(provider);Instant sent=Instant.now().minusSeconds(7200);String message=id(sent);
        var event=events.persistAuthenticated(provider,integration,"live","{}","live");
        var result=messages.handle(new InboundMessageCommandHandler.Command(event.id(),integration,channel,IntegrationProvider.valueOf(provider),"live","C-RECOVERY",message,message,"human","live",InboundMessageCommandHandler.Mutation.CREATE,sent,"live"));
        var before=jdbc.queryForMap("SELECT first_response_started_at,first_response_due_at FROM case_sla");
        store.start(integration,null,"manual",Instant.now());Task task=store.claim().orElseThrow();
        store.commit(task,adapter(),new Page(List.of(payload(message,sent,message)),List.of(),null));processInbound();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM messages",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT recovery_source FROM messages WHERE id=?",String.class,result.messageId())).isNull();
        assertThat(jdbc.queryForMap("SELECT first_response_started_at,first_response_due_at FROM case_sla")).isEqualTo(before);
        assertThat(store.view(integration).runs().getFirst().channels().getFirst().added()).isZero();
    }
    @ParameterizedTest @CsvSource({"SLACK","TEAMS"})
    void twoHourProviderOutagePreservesCheckpointAndResumesWithFencedLease(String provider) {
        fixture(provider);Instant now=Instant.now();store.start(integration,null,"manual",now);
        Task original=store.claim().orElseThrow();store.commit(original,adapter(),new Page(List.of(),List.of(),"second-page"));
        Task second=store.claim().orElseThrow();assertThat(second.cursor()).isEqualTo("second-page");
        // 240 deterministic 30-second failures model two hours without sleeps or shortened production retries.
        for(int i=0;i<240;i++) {store.fail(second,"SYNTHETIC_PROVIDER_HTTP_503",Duration.ofSeconds(30));
            assertThat(store.view(integration).runs().getFirst().channels().getFirst().status()).isEqualTo("WAITING");
            jdbc.update("UPDATE integration_resync_tasks SET next_attempt_at=CURRENT_TIMESTAMP-INTERVAL '1 second'");second=store.claim().orElseThrow();assertThat(second.cursor()).isEqualTo("second-page");}
        jdbc.update("UPDATE integration_resync_tasks SET lease_until=CURRENT_TIMESTAMP-INTERVAL '1 second'");
        Task restarted=store.claim().orElseThrow();assertThat(restarted.lease()).isNotEqualTo(second.lease());
        store.commit(second,adapter(),new Page(List.of(),List.of(),null));assertThat(store.view(integration).runs().getFirst().channels().getFirst().status()).isEqualTo("RUNNING");
        store.commit(restarted,adapter(),new Page(List.of(),List.of(),null));assertThat(store.view(integration).runs().getFirst().channels().getFirst().status()).isEqualTo("SUCCEEDED");
    }
    @ParameterizedTest @CsvSource({"SLACK","TEAMS"})
    void overdueScheduleUsesCurrentWindowAndPermissionFailureIsPerChannel(String provider) {
        fixture(provider);store.save(integration,new HistoryRecoveryStore.Settings(true,true,30,2880,null,null));
        jdbc.update("""
                INSERT INTO channels(integration_id,external_channel_id,name,customer_id,grouping_strategy,active,metadata_json)
                SELECT integration_id,'C-SECOND','second',customer_id,grouping_strategy,TRUE,metadata_json FROM channels WHERE id=?
                """,channel);
        jdbc.update("UPDATE integration_resync_settings SET next_run_at=CURRENT_TIMESTAMP-INTERVAL '2 hours'");
        Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        // Reconstruct the scheduler's repository after the simulated outage; no in-memory deadline survives.
        var restarted=new HistoryRecoveryStore(jdbc,json);
        new org.springframework.transaction.support.TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class))
                .executeWithoutResult(status->restarted.schedule(now));Task task=store.claim().orElseThrow();
        assertThat(task.source()).isEqualTo("automatic");assertThat(task.from()).isEqualTo(now.minusSeconds(172800));
        assertThat(store.settings(integration).nextRunAt()).isEqualTo(now.plusSeconds(1800));
        store.fail(task,provider+"_HISTORY_HTTP_403",null);
        Task independent=store.claim().orElseThrow();
        store.commit(independent,adapter(),new Page(List.of(),List.of(),null));
        var channels=store.view(integration).runs().getFirst().channels();
        assertThat(channels).hasSize(2);
        assertThat(channels.stream().filter(c->c.channelId().equals(task.channelId())).findFirst().orElseThrow())
                .satisfies(c->{assertThat(c.status()).isEqualTo("INCOMPLETE");assertThat(c.errorCode()).isEqualTo(provider+"_HISTORY_HTTP_403");});
        assertThat(channels.stream().filter(c->c.channelId().equals(independent.channelId())).findFirst().orElseThrow().status()).isEqualTo("SUCCEEDED");
        store.save(integration,new HistoryRecoveryStore.Settings(false,false,30,2880,null,null));
        assertThat(store.settings(integration).nextRunAt()).isNull();
    }
    @ParameterizedTest @CsvSource({"SLACK","TEAMS"})
    void concurrentFirstWebhookAndResyncInsertOneMessageCaseAndSlaEvenAfterResolution(String provider) throws Exception {
        fixture(provider);Instant sent=Instant.now().minusSeconds(7200);String message=id(sent);
        store.start(integration,null,"manual",Instant.now());Task task=store.claim().orElseThrow();
        store.commit(task,adapter(),new Page(List.of(payload(message,sent,message)),List.of(),null));
        UUID recovery=jdbc.queryForObject("SELECT id FROM inbound_events WHERE resync_task_id=?",UUID.class,task.id());
        var webhook=events.persistAuthenticated(provider,integration,"live-race","{}","live-race");
        var recovered=new InboundMessageCommandHandler.Command(recovery,integration,channel,IntegrationProvider.valueOf(provider),"recovery-race","C-RECOVERY",message,message,"human","recovered",InboundMessageCommandHandler.Mutation.CREATE,sent,"resync-race");
        var normal=new InboundMessageCommandHandler.Command(webhook.id(),integration,channel,IntegrationProvider.valueOf(provider),"live-race","C-RECOVERY",message,message,"human","live",InboundMessageCommandHandler.Mutation.CREATE,sent,"live-race");
        var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->{start.await();return messages.handle(recovered);});
            var second=executor.submit(()->{start.await();return messages.handle(normal);});start.countDown();
            var r=first.get();var w=second.get();assertThat(r.caseId()).isEqualTo(w.caseId());
            assertThat(r.created() ^ w.created()).isTrue();
            assertThat(jdbc.queryForObject("SELECT recovery_source FROM messages",String.class)).isEqualTo(r.created()?"manual":null);
            jdbc.update("UPDATE cases SET status='RESOLVED',resolution_category='SOLVED',resolved_at=CURRENT_TIMESTAMP WHERE id=?",r.caseId());
            assertThat(messages.handle(normal).caseId()).isEqualTo(r.caseId());
            assertThat(messages.handle(recovered).created()).isFalse();
        }
        for(String table:List.of("messages","cases","case_sla"))assertThat(jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class)).isEqualTo(1);
    }
    @Test void rateLimitDefersNewThreadTasksAndDisabledChannelsAreNotScanned() {
        fixture("SLACK");jdbc.update("UPDATE channels SET ignored=TRUE WHERE id=?",channel);
        store.start(integration,null,"manual",Instant.now());assertThat(store.claim()).isEmpty();
        jdbc.update("UPDATE channels SET ignored=FALSE WHERE id=?",channel);
        store.start(integration,null,"manual",Instant.now());Task root=store.claim().orElseThrow();
        store.commit(root,adapter(),new Page(List.of(),List.of("old-thread"),"next-page"));
        Task page=store.claim().orElseThrow();store.fail(page,"SLACK_RATE_LIMITED",Duration.ofMinutes(2));
        assertThat(store.claim()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT provider_retry_at>CURRENT_TIMESTAMP+INTERVAL '1 minute' FROM integration_resync_settings",Boolean.class)).isTrue();
    }
}
