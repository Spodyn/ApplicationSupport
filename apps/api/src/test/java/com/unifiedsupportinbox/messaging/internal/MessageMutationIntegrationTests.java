package com.unifiedsupportinbox.messaging.internal;

import static org.assertj.core.api.Assertions.*;
import com.unifiedsupportinbox.*;
import com.unifiedsupportinbox.integration.IntegrationProvider;
import com.unifiedsupportinbox.messaging.*;
import com.unifiedsupportinbox.messaging.MessageDeliveryProvider.*;
import com.unifiedsupportinbox.sla.CaseSlaInitializer;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
class MessageMutationIntegrationTests {
    static final PostgreSQLContainer POSTGRES=TestInfrastructure.postgres();
    static ConfigurableApplicationContext context;
    static JdbcTemplate jdbc;
    static MessageMutationService mutations;
    static MessageMutationWorker worker;
    static FakeProvider provider;
    static MutableClock clock;
    @BeforeAll static void start() {
        POSTGRES.start();
        context=new SpringApplicationBuilder(UsiApiApplication.class,Fixtures.class).profiles("test").run(
                "--server.port=0","--spring.datasource.url="+POSTGRES.getJdbcUrl(),"--spring.datasource.username="+POSTGRES.getUsername(),
                "--spring.datasource.password="+POSTGRES.getPassword(),"--spring.datasource.driver-class-name="+POSTGRES.getDriverClassName(),
                "--spring.flyway.enabled=true","--spring.jpa.hibernate.ddl-auto=validate","--spring.session.jdbc.initialize-schema=never",
                "--usi.bootstrap-admin.enabled=false","--usi.providers.slack.outbound.enabled=false","--usi.providers.teams.outbound.enabled=false",
                "--usi.messages.delivery-worker.enabled=false");
        jdbc=context.getBean(JdbcTemplate.class); mutations=context.getBean(MessageMutationService.class);
        worker=context.getBean(MessageMutationWorker.class);provider=context.getBean(FakeProvider.class);clock=context.getBean(MutableClock.class);
    }
    @AfterAll static void stop(){if(context!=null)context.close();POSTGRES.stop();}
    @BeforeEach void reset(){TestInfrastructure.resetPostgres(POSTGRES);provider.reset();clock.time=Instant.parse("2026-12-07T10:00:00Z");}

    @ParameterizedTest @EnumSource(value=IntegrationProvider.class,names={"SLACK","TEAMS"})
    void confirmsEditAndDeleteOnlyAfterProviderReceiptWithoutChangingWorkflowSlaOrSendTime(IntegrationProvider type) {
        Fixture f=fixture(type);String state=businessSnapshot(f);
        var accepted=mutations.request(f.message,f.actor,"edit","EDIT","after",0,"mutation-test");
        UUID edit=id(accepted);
        assertThat(body(f)).isEqualTo("before");assertThat(edited(f)).isNull();
        assertThat(mutations.views(List.of(f.message),f.actor).get(f.message).state()).isEqualTo("QUEUED");
        assertThat(id(mutations.request(f.message,f.actor,"edit","EDIT","after",0,"mutation-test"))).isEqualTo(edit);
        assertThat(outbox(edit)).isEqualTo(1);
        worker.process(edit);
        assertThat(body(f)).isEqualTo("after");assertThat(edited(f)).isNotNull();assertThat(state(edit)).isEqualTo("CONFIRMED");
        assertThat(provider.calls.get()).isEqualTo(1);assertThat(provider.transactionSeen).isFalse();
        assertThat(jdbc.queryForObject("SELECT body FROM message_content_versions WHERE message_id=? AND revision=0",String.class,f.message)).isEqualTo("before");
        assertThat(businessSnapshot(f)).isEqualTo(state);
        UUID deletion=id(mutations.request(f.message,f.actor,"delete","DELETE",null,1,"mutation-test"));
        assertThat(deleted(f)).isNull();worker.process(deletion);
        assertThat(deleted(f)).isNotNull();assertThat(body(f)).isEqualTo("after");
        assertThat(businessSnapshot(f)).isEqualTo(state);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM message_content_versions WHERE message_id=?",Integer.class,f.message)).isEqualTo(2);
        assertThatThrownBy(()->mutations.request(f.message,f.actor,"resurrection","EDIT","resurrect",2,"mutation-test")).isInstanceOf(ApiProblemException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE entity_id=? AND action='SUPPORT_MESSAGE_DELETED'",Integer.class,f.message)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT metadata_json::text FROM audit_events WHERE entity_id=? AND action='SUPPORT_MESSAGE_EDITED'",String.class,f.message)).doesNotContain("before","after");
    }

    @Test void transientRetryHonorsDueTimeAndKeepsOneLogicalOperation() {
        Fixture f=fixture(IntegrationProvider.SLACK);
        provider.result=MutationResult.retry("SLACK_RATE_LIMITED",Duration.ofMinutes(2));
        UUID op=id(mutations.request(f.message,f.actor,"retry","EDIT","after",0,"test"));worker.process(op);
        assertThat(state(op)).isEqualTo("RETRY");assertThat(body(f)).isEqualTo("before");
        assertThat(mutations.redispatch()).isZero();clock.advance(Duration.ofSeconds(119));assertThat(mutations.redispatch()).isZero();
        clock.advance(Duration.ofSeconds(1));assertThat(mutations.redispatch()).isEqualTo(1);
        provider.result=MutationResult.confirmed();worker.process(op);worker.process(op);
        assertThat(body(f)).isEqualTo("after");assertThat(provider.calls.get()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM message_mutations",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action='SUPPORT_MESSAGE_EDITED'",Integer.class)).isEqualTo(1);
    }

    @Test void exhaustedSafeFailureCanBeRetriedButUnknownOutcomeIsNeverBlindlyReplayed() {
        Fixture f=fixture(IntegrationProvider.TEAMS);
        UUID op=id(mutations.request(f.message,f.actor,"exhaust","EDIT","after",0,"test"));
        jdbc.update("UPDATE message_mutations SET max_attempts=1 WHERE id=?",op);
        provider.result=MutationResult.retry("TEAMS_RATE_LIMITED",Duration.ofSeconds(1));worker.process(op);
        assertThat(state(op)).isEqualTo("FAILED");assertThat(mutations.views(List.of(f.message),f.actor).get(f.message).canRetry()).isTrue();
        assertThat(id(mutations.retry(f.message,op,f.actor,"manual-retry"))).isEqualTo(op);
        provider.result=MutationResult.uncertain("TEAMS_MUTATION_UNCONFIRMED");worker.process(op);
        assertThat(state(op)).isEqualTo("UNCERTAIN");assertThat(body(f)).isEqualTo("before");
        assertThat(mutations.views(List.of(f.message),f.actor).get(f.message).canRetry()).isFalse();
        clock.advance(Duration.ofDays(1));assertThat(mutations.redispatch()).isZero();worker.process(op);assertThat(provider.calls.get()).isEqualTo(2);
        assertThatThrownBy(()->mutations.retry(f.message,op,f.actor,"unsafe-retry")).isInstanceOf(ApiProblemException.class);
        mutations.observe(IntegrationProvider.TEAMS,f.integration,"channel","provider-id","BOT_TEST","after",false,clock.instant());
        assertThat(state(op)).isEqualTo("CONFIRMED");assertThat(body(f)).isEqualTo("after");
    }

    @Test void expiredWorkerLeaseBlocksNewOperationsAndAcceptsOnlyItsFencedConfirmation() {
        Fixture f=fixture(IntegrationProvider.SLACK);
        UUID op=id(mutations.request(f.message,f.actor,"lease","EDIT","after",0,"test"));
        var claim=mutations.claim(op);clock.advance(Duration.ofMinutes(1));assertThat(mutations.redispatch()).isEqualTo(1);
        assertThat(state(op)).isEqualTo("UNCERTAIN");worker.process(op);assertThat(provider.calls.get()).isZero();
        assertThatThrownBy(()->mutations.request(f.message,f.actor,"new","DELETE",null,0,"test")).isInstanceOf(ApiProblemException.class);
        mutations.finish(claim,MutationResult.confirmed());assertThat(state(op)).isEqualTo("CONFIRMED");
        UUID newer=id(mutations.request(f.message,f.actor,"newer","EDIT","newer",1,"test"));worker.process(newer);
        mutations.finish(claim,MutationResult.confirmed());assertThat(body(f)).isEqualTo("newer");
    }

    @ParameterizedTest @EnumSource(value=IntegrationProvider.class,names={"SLACK","TEAMS"})
    void callbacksCannotDuplicateResurrectOrRollBackNewerEdits(IntegrationProvider type) {
        Fixture f=fixture(type);Instant originalEditTime=clock.instant();
        UUID op=id(mutations.request(f.message,f.actor,"callback","EDIT","after",0,"test"));
        provider.before=()->mutations.observe(type,f.integration,"channel",reference(type),"BOT_TEST","after",false,clock.instant());
        worker.process(op);assertThat(body(f)).isEqualTo("after");assertThat(state(op)).isEqualTo("CONFIRMED");
        mutations.observe(type,f.integration,"channel",reference(type),"BOT_TEST","after",false,clock.instant());
        assertThat(jdbc.queryForObject("SELECT mutation_revision FROM messages WHERE id=?",Long.class,f.message)).isEqualTo(1);
        provider.before=()->{};clock.advance(Duration.ofSeconds(1));
        UUID next=id(mutations.request(f.message,f.actor,"next","EDIT","newer",1,"test"));worker.process(next);
        mutations.observe(type,f.integration,"channel",reference(type),"BOT_TEST","after",false,originalEditTime);
        assertThat(body(f)).isEqualTo("newer");
        mutations.observe(type,f.integration,"channel",reference(type),"MODERATOR",null,true,clock.instant().plusSeconds(2));
        assertThat(deleted(f)).isNotNull();
        mutations.observe(type,f.integration,"channel",reference(type),"BOT_TEST","bad resurrection",false,clock.instant().plusSeconds(3));
        assertThat(body(f)).isEqualTo("newer");assertThat(provider.calls.get()).isEqualTo(2);
    }

    @Test void genuinelyNewExternalEditCanReuseAnOlderBodyWithoutBeingMistakenForAnEcho() {
        Fixture f=fixture(IntegrationProvider.SLACK);
        worker.process(id(mutations.request(f.message,f.actor,"old-body","EDIT","after",0,"test")));
        clock.advance(Duration.ofSeconds(1));worker.process(id(mutations.request(f.message,f.actor,"new-body","EDIT","newer",1,"test")));
        clock.advance(Duration.ofSeconds(1));
        mutations.observe(IntegrationProvider.SLACK,f.integration,"channel",reference(IntegrationProvider.SLACK),"BOT_TEST","after",false,clock.instant());
        assertThat(body(f)).isEqualTo("after");assertThat(provider.calls.get()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT mutation_revision FROM messages WHERE id=?",Long.class,f.message)).isEqualTo(3);
    }

    @Test void staleMatchingCallbackCannotConfirmANewAttempt() {
        Fixture f=fixture(IntegrationProvider.SLACK);Instant earlier=clock.instant();
        UUID first=id(mutations.request(f.message,f.actor,"first-body","EDIT","after",0,"test"));worker.process(first);
        clock.advance(Duration.ofSeconds(1));
        UUID second=id(mutations.request(f.message,f.actor,"second-body","EDIT","newer",1,"test"));worker.process(second);
        clock.advance(Duration.ofSeconds(1));
        UUID current=id(mutations.request(f.message,f.actor,"revert-body","EDIT","after",2,"test"));
        provider.before=()->mutations.observe(IntegrationProvider.SLACK,f.integration,"channel",reference(IntegrationProvider.SLACK),"BOT_TEST","after",false,earlier);
        provider.result=MutationResult.failed("SLACK_PERMISSION_DENIED");worker.process(current);
        assertThat(state(current)).isEqualTo("FAILED");assertThat(body(f)).isEqualTo("newer");
    }

    @Test void olderUnknownExternalEditCannotRollBackAConfirmedProviderMutation() {
        Fixture f=fixture(IntegrationProvider.SLACK);Instant earlier=clock.instant();clock.advance(Duration.ofSeconds(5));
        UUID op=id(mutations.request(f.message,f.actor,"confirmed-newer","EDIT","after",0,"test"));worker.process(op);
        mutations.observe(IntegrationProvider.SLACK,f.integration,"channel",reference(IntegrationProvider.SLACK),"BOT_TEST","older external content",false,earlier);
        assertThat(body(f)).isEqualTo("after");assertThat(state(op)).isEqualTo("CONFIRMED");
    }

    @Test void deletionCallbackWinsAgainstAnInFlightEdit() {
        Fixture f=fixture(IntegrationProvider.SLACK);
        UUID op=id(mutations.request(f.message,f.actor,"race-delete","EDIT","after",0,"test"));
        provider.before=()->mutations.observe(IntegrationProvider.SLACK,f.integration,"channel",reference(IntegrationProvider.SLACK),"BOT_TEST",null,true,clock.instant());
        worker.process(op);assertThat(deleted(f)).isNotNull();assertThat(body(f)).isEqualTo("before");assertThat(state(op)).isEqualTo("FAILED");
    }

    @Test void queuedCommandsRecheckAuthorAndActiveAccountAndNeverMutateIncomingOrUnsupportedMessages() {
        Fixture f=fixture(IntegrationProvider.SLACK);UUID other=user("other");
        assertThatThrownBy(()->mutations.request(f.message,other,"other","EDIT","bad",0,"test")).isInstanceOf(ApiProblemException.class);
        jdbc.update("UPDATE users SET role='ADMIN' WHERE id=?",other);
        assertThatThrownBy(()->mutations.request(f.message,other,"admin","DELETE",null,0,"test")).isInstanceOf(ApiProblemException.class);
        UUID op=id(mutations.request(f.message,f.actor,"disabled","EDIT","after",0,"test"));
        jdbc.update("UPDATE users SET active=false WHERE id=?",f.actor);worker.process(op);
        assertThat(provider.calls.get()).isZero();assertThat(state(op)).isEqualTo("FAILED");assertThat(body(f)).isEqualTo("before");
        jdbc.update("UPDATE users SET active=true WHERE id=?",f.actor);
        jdbc.update("UPDATE messages SET kind='CUSTOMER',inbound=true,author_user_id=NULL,author_external_id='human',delivery_status=NULL,provider_created_at=created_at WHERE id=?",f.message);
        assertThatThrownBy(()->mutations.request(f.message,f.actor,"customer","DELETE",null,0,"test")).isInstanceOf(ApiProblemException.class);
        Fixture telegram=fixture(IntegrationProvider.TELEGRAM);
        assertThat(mutations.views(List.of(telegram.message),telegram.actor).get(telegram.message).canEdit()).isFalse();
        assertThatThrownBy(()->mutations.request(telegram.message,telegram.actor,"unsupported","EDIT","after",0,"test")).isInstanceOf(ApiProblemException.class);
    }

    @Test void deliveryAndMissingTeamsIdentitiesAreNotMutationTargets() {
        Fixture f=fixture(IntegrationProvider.TEAMS);
        jdbc.update("DELETE FROM message_mutation_targets WHERE message_id=?",f.message);
        jdbc.update("UPDATE messages SET external_message_id=NULL WHERE id=?",f.message);
        var missing=mutations.views(List.of(f.message),f.actor).get(f.message);
        assertThat(missing.editUnavailable()).isEqualTo("PROVIDER_TARGET_UNAVAILABLE");assertThat(missing.canDelete()).isFalse();
        assertThatThrownBy(()->mutations.request(f.message,f.actor,"invented","EDIT","after",0,"test")).isInstanceOf(ApiProblemException.class);
        Fixture slack=fixture(IntegrationProvider.SLACK);
        for(String delivery:List.of("QUEUED","SENDING","FAILED")) {
            jdbc.update("UPDATE messages SET delivery_status=? WHERE id=?",delivery,slack.message);
            assertThatThrownBy(()->mutations.request(slack.message,slack.actor,delivery,"EDIT","after",0,"test")).isInstanceOf(ApiProblemException.class);
        }
        assertThat(provider.calls.get()).isZero();
    }

    @Test void concurrentCommandsSerializeAndStaleRevisionCannotOverwriteContent() throws Exception {
        Fixture f=fixture(IntegrationProvider.SLACK);var barrier=new CyclicBarrier(2);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var futures=new ArrayList<Future<Boolean>>();
            for(int i=0;i<2;i++) { final int index=i; futures.add(executor.submit(()->{
                barrier.await();try {mutations.request(f.message,f.actor,"concurrent-"+index,"EDIT","after-"+index,0,"test");return true;}
                catch(ApiProblemException conflict){assertThat(conflict.code()).isEqualTo(ApiProblemCode.CONFLICT);return false;}
            })); }
            assertThat(List.of(futures.get(0).get(),futures.get(1).get())).containsExactlyInAnyOrder(true,false);
        }
        UUID op=jdbc.queryForObject("SELECT id FROM message_mutations",UUID.class);worker.process(op);
        assertThatThrownBy(()->mutations.request(f.message,f.actor,"stale","EDIT","stale",0,"test")).isInstanceOf(ApiProblemException.class);
    }

    @ParameterizedTest @EnumSource(value=IntegrationProvider.class,names={"SLACK","TEAMS"})
    void attachedFilesAreRetainedAndCapabilitiesDistinguishTextEditsFromCompleteDeletion(IntegrationProvider type) {
        Fixture f=fixture(type);UUID attachment=UUID.randomUUID();
        jdbc.update("INSERT INTO attachments(id,message_id,storage_key,original_filename,size_bytes,sha256,scan_status) VALUES (?,?,?,'report.txt',4,?,'CLEAN')",attachment,f.message,"synthetic/"+attachment,"a".repeat(64));
        if(type==IntegrationProvider.TEAMS)jdbc.update("UPDATE message_mutation_targets SET attachment_payload=?::jsonb WHERE message_id=?","[{\"contentType\":\"application/vnd.microsoft.card.adaptive\",\"content\":{\"type\":\"AdaptiveCard\"}}]",f.message);
        var view=mutations.views(List.of(f.message),f.actor).get(f.message);
        assertThat(view.canEdit()).isTrue();assertThat(view.canDelete()).isFalse();
        assertThat(view.deleteUnavailable()).isEqualTo("MULTIPLE_PROVIDER_RESOURCES");
        assertThatThrownBy(()->mutations.request(f.message,f.actor,"partial-delete","DELETE",null,0,"test")).isInstanceOf(ApiProblemException.class);
        UUID edit=id(mutations.request(f.message,f.actor,"attached-edit","EDIT","after",0,"test"));worker.process(edit);
        assertThat(jdbc.queryForObject("SELECT scan_status FROM attachments WHERE id=?",String.class,attachment)).isEqualTo("CLEAN");
        jdbc.update("UPDATE messages SET body='' WHERE id=?",f.message);
        if(type==IntegrationProvider.TEAMS) {
            assertThat(mutations.views(List.of(f.message),f.actor).get(f.message).canEdit()).isTrue();
            UUID caption=id(mutations.request(f.message,f.actor,"attachment-caption","EDIT","New attachment caption",1,"test"));worker.process(caption);
            assertThat(body(f)).isEqualTo("New attachment caption");
        } else assertThat(mutations.views(List.of(f.message),f.actor).get(f.message).editUnavailable()).isEqualTo("NO_TEXT_RESOURCE");
        jdbc.update("UPDATE attachments SET scan_status='INFECTED' WHERE id=?",attachment);
        assertThat(mutations.views(List.of(f.message),f.actor).get(f.message).editUnavailable()).isEqualTo("ATTACHMENT_NOT_CLEAN");
        assertThat(provider.calls.get()).isEqualTo(type==IntegrationProvider.TEAMS?2:1);
    }

    @Test void lifetimeIgnoreRestrictionsAndIntegrationDeactivationAreRecheckedBeforeProviderIo() {
        Fixture f=fixture(IntegrationProvider.SLACK);
        jdbc.update("INSERT INTO case_ignore_votes(case_id,user_id,weight,active,deactivated_at) VALUES (?,?,1,false,now())",f.caseId,f.actor);
        assertThatThrownBy(()->mutations.request(f.message,f.actor,"ignored","EDIT","after",0,"test")).isInstanceOf(ApiProblemException.class);
        assertThat(mutations.views(List.of(f.message),f.actor).get(f.message).canEdit()).isFalse();
        Fixture enabled=fixture(IntegrationProvider.TEAMS);
        UUID op=id(mutations.request(enabled.message,enabled.actor,"disable","DELETE",null,0,"test"));
        jdbc.update("UPDATE integrations SET status='DISABLED' WHERE id=?",enabled.integration);worker.process(op);
        assertThat(state(op)).isEqualTo("FAILED");assertThat(provider.calls.get()).isZero();assertThat(deleted(enabled)).isNull();
    }

    private static Fixture fixture(IntegrationProvider type) {
        UUID actor=user("author");String namespace=UUID.randomUUID().toString();
        UUID customer=jdbc.queryForObject("INSERT INTO customers(name) VALUES (?) RETURNING id",UUID.class,namespace);
        UUID integration=jdbc.queryForObject("INSERT INTO integrations(provider,display_name,status,health,secret_ref) VALUES (?,?,'ENABLED','HEALTHY','test-only/mutations') RETURNING id",UUID.class,type.name(),namespace);
        UUID channel=jdbc.queryForObject("INSERT INTO channels(integration_id,external_channel_id,name,customer_id,grouping_strategy) VALUES (?,'channel',?,?,?) RETURNING id",UUID.class,integration,namespace,customer,type==IntegrationProvider.TEAMS?"TEAMS_ROOT_REPLIES":"SLACK_ROOT_THREAD");
        UUID caseId=jdbc.queryForObject("INSERT INTO cases(customer_id,integration_id,channel_id,provider,external_conversation_id,external_thread_key,status,owner_user_id,claimed_at,waiting_until) VALUES (?,?,?,?,'channel','root','WAITING_FOR_CUSTOMER',?,now(),now()+interval '1 day') RETURNING id",UUID.class,customer,integration,channel,type.name(),actor);
        UUID message=jdbc.queryForObject("INSERT INTO messages(case_id,external_message_id,external_thread_key,kind,author_user_id,body,inbound,delivery_status,correlation_id) VALUES (?,?,'root','SUPPORT',?,'before',false,'SENT','mutation-test') RETURNING id",UUID.class,caseId,reference(type),actor);
        jdbc.update("INSERT INTO message_mutation_targets(message_id,provider_message_ref,conversation_id,sender_id,service_url,context_type,credential_ref) VALUES (?,?,'connector-conversation','BOT_TEST','https://smba.trafficmanager.net/emea','standardChannel','test-only/mutations')",message,reference(type));
        context.getBean(CaseSlaInitializer.class).initialize(caseId,Instant.now());
        context.getBean(com.unifiedsupportinbox.sla.CaseSlaResponseRecorder.class).recordProviderAcceptedResponse(caseId,clock.instant());
        new org.springframework.transaction.support.TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class)).executeWithoutResult(ignored->{
            jdbc.queryForObject("SELECT id FROM cases WHERE id=? FOR UPDATE",UUID.class,caseId);
            context.getBean(com.unifiedsupportinbox.sla.CaseOperationalDeadlines.class).followUp(caseId,message,clock.instant(),clock.instant().plus(Duration.ofDays(1)));
        });
        jdbc.update("INSERT INTO case_next_response_cycles(case_id,opening_message_id,started_at,warning_at,due_at,ended_at,outcome,response_message_id) VALUES (?,?,?::timestamptz,?::timestamptz,?::timestamptz,?::timestamptz,'RESPONDED',?)",caseId,UUID.randomUUID(),"2026-12-04T10:00:00Z","2026-12-04T10:22:30Z","2026-12-04T10:30:00Z","2026-12-04T11:00:00Z",message);
        return new Fixture(actor,integration,caseId,message);
    }
    private static String reference(IntegrationProvider type){return type==IntegrationProvider.SLACK?"1790000000.123456":"provider-id";}
    private static UUID user(String name){return jdbc.queryForObject("INSERT INTO users(email,display_name,role,active) VALUES (?,?,'USER',true) RETURNING id",UUID.class,name+UUID.randomUUID()+"@example.test",name);}
    private static UUID id(IdempotencyResult result){assertThat(result.status()).isEqualTo(202);return UUID.fromString(result.body().path("operationId").asText());}
    private static String body(Fixture f){return jdbc.queryForObject("SELECT body FROM messages WHERE id=?",String.class,f.message);}
    private static Object edited(Fixture f){return jdbc.queryForObject("SELECT edited_at FROM messages WHERE id=?",Object.class,f.message);}
    private static Object deleted(Fixture f){return jdbc.queryForObject("SELECT deleted_at FROM messages WHERE id=?",Object.class,f.message);}
    private static String state(UUID id){return jdbc.queryForObject("SELECT state FROM message_mutations WHERE id=?",String.class,id);}
    private static int outbox(UUID id){return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE type='message.mutation.requested' AND payload_json->>'operationId'=?",Integer.class,id.toString());}
    private static String businessSnapshot(Fixture f){return jdbc.queryForObject("""
            SELECT jsonb_build_object('status',c.status,'owner',c.owner_user_id,'version',c.version,'waiting',c.waiting_until,
              'activity',c.last_activity_at,'sent',m.created_at,'providerSent',m.provider_created_at,
              'sla',(SELECT to_jsonb(s) FROM case_sla s WHERE s.case_id=c.id),
              'cycles',(SELECT jsonb_agg(to_jsonb(n) ORDER BY n.id) FROM case_next_response_cycles n WHERE n.case_id=c.id),
              'deadlines',(SELECT jsonb_agg(to_jsonb(d) ORDER BY d.id) FROM case_operational_deadlines d WHERE d.case_id=c.id))::text
            FROM cases c JOIN messages m ON m.case_id=c.id WHERE m.id=?
            """,String.class,f.message);}
    record Fixture(UUID actor,UUID integration,UUID caseId,UUID message){}

    @TestConfiguration(proxyBeanMethods=false) static class Fixtures {
        @Bean FakeProvider mutationProvider(){return new FakeProvider();}
        @Bean MutableClock mutationClock(){return new MutableClock();}
    }
    static class FakeProvider implements MessageDeliveryProvider {
        MutationResult result=MutationResult.confirmed();AtomicInteger calls=new AtomicInteger();boolean transactionSeen;Runnable before=()->{};
        void reset(){result=MutationResult.confirmed();calls.set(0);transactionSeen=false;before=()->{};}
        public boolean supports(IntegrationProvider type){return type!=IntegrationProvider.TELEGRAM;}
        public DeliveryResult deliver(DeliveryCommand command){throw new AssertionError("Mutation must never send a new message");}
        public MutationCapability mutationCapability(MutationTarget target,boolean attachments,boolean text){boolean edit=text||(attachments&&"provider-id".equals(target.providerMessageRef())&&!"[]".equals(target.attachmentPayload()));return new MutationCapability(edit,!attachments,edit?null:"NO_TEXT_RESOURCE",attachments?"MULTIPLE_PROVIDER_RESOURCES":null);}
        public MutationResult mutate(MutationCommand command){calls.incrementAndGet();transactionSeen|=TransactionSynchronizationManager.isActualTransactionActive();before.run();return result;}
    }
    static class MutableClock extends Clock {
        Instant time=Instant.parse("2026-12-07T10:00:00Z");void advance(Duration duration){time=time.plus(duration);}
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return time;}
    }
}
