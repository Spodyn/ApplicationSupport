package com.unifiedsupportinbox.provider.teams.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.unifiedsupportinbox.InboundEventStore;
import com.unifiedsupportinbox.integration.IntegrationProvider;
import com.unifiedsupportinbox.messaging.InboundMessageCommandHandler;
import com.unifiedsupportinbox.messaging.InboundMessageCommandHandler.Command;
import com.unifiedsupportinbox.messaging.InboundMessageCommandHandler.Mutation;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties="usi.providers.teams.client-id=bot")
@ActiveProfiles("test")
class TeamsInboxIntegrationTests {
    static final org.testcontainers.postgresql.PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();
    @Autowired JdbcTemplate jdbc;
    @Autowired InboundMessageCommandHandler handler;
    @Autowired InboundEventStore events;
    @Autowired TeamsInboundDeliveryService deliveries;
    @Autowired TeamsInboundWorker worker;
    @Autowired TeamsInboundAttachments transfers;
    @Autowired TeamsOutboundAcceptance acceptance;
    @Autowired com.unifiedsupportinbox.storage.AttachmentMetadataCatalog metadata;
    @org.springframework.test.context.bean.override.mockito.MockitoBean TeamsFilesHttpClient files;
    @org.springframework.test.context.bean.override.mockito.MockitoBean com.unifiedsupportinbox.storage.AttachmentObjectStorage objects;
    @org.springframework.test.context.bean.override.mockito.MockitoBean com.unifiedsupportinbox.storage.AttachmentSecurityService scanner;
    static final String TEAM="11111111-1111-4111-8111-111111111111";
    UUID integration, channel;

    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry registry) {
        POSTGRES.start();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("usi.outbox.relay-enabled", () -> false);
        registry.add("usi.bootstrap-admin.enabled", () -> false);
    }
    @AfterAll static void stop() { POSTGRES.stop(); }
    @BeforeEach void fixture() {
        reset(files,objects,scanner);
        TestInfrastructure.resetPostgres(POSTGRES);
        integration = jdbc.queryForObject("INSERT INTO integrations(provider, display_name, status, health) VALUES ('TEAMS','Synthetic Teams','ENABLED','HEALTHY') RETURNING id", UUID.class);
        jdbc.update("UPDATE integrations SET created_at='2026-11-19T00:00:00Z' WHERE id=?",integration);
        jdbc.update("UPDATE integrations SET secret_ref='synthetic/teams' WHERE id=?",integration);
        UUID customer = jdbc.queryForObject("INSERT INTO customers(name,external_ref) VALUES ('Synthetic customer','teams-test') RETURNING id", UUID.class);
        jdbc.update("INSERT INTO teams_instance_binding(integration_id,team_id,default_customer_id) VALUES (?,?::uuid,?)",integration,TEAM,customer);
        channel = jdbc.queryForObject("INSERT INTO channels(integration_id,external_channel_id,name,customer_id,grouping_strategy,active) VALUES (?,'conversation','support',?,'TEAMS_ROOT_REPLIES',TRUE) RETURNING id", UUID.class, integration, customer);
        jdbc.update("UPDATE channels SET metadata_json='{\"teamsContextType\":\"standardChannel\"}'::jsonb WHERE id=?",channel);
    }
    @Test void ownBotEditAndDeleteCallbacksDoNotCreateCustomerMessagesOrFeedbackMutations() {
        var first=handler.handle(command("customer-root","root"));
        UUID author=jdbc.queryForObject("INSERT INTO users(email,display_name,role,active) VALUES ('bot-callback@example.test','Author','USER',true) RETURNING id",UUID.class);
        UUID message=jdbc.queryForObject("INSERT INTO messages(case_id,kind,author_user_id,body,inbound,delivery_status,external_message_id,correlation_id) VALUES (?,'SUPPORT',?,'before',false,'SENT','outbound-activity','callback') RETURNING id",UUID.class,first.caseId(),author);
        jdbc.update("INSERT INTO message_mutation_targets(message_id,provider_message_ref,conversation_id,sender_id,service_url,context_type) VALUES (?,'outbound-activity','conversation','28:bot','https://smba.trafficmanager.net/emea/','standardChannel')",message);
        var json=new tools.jackson.databind.ObjectMapper();
        for(int index=0;index<2;index++) {
            var body=(tools.jackson.databind.node.ObjectNode)json.readTree(activity("outbound-activity","root","channel"));
            body.put("type","messageUpdate").put("text","after");body.withObjectProperty("from").put("id","28:bot");
            var event=deliveries.persistAndWake(integration,"bot-edit-"+index,json.writeValueAsString(body),"bot-edit");worker.process(event.id());
            assertThat(jdbc.queryForObject("SELECT processing_outcome FROM inbound_events WHERE id=?",String.class,event.id())).isEqualTo("IGNORED_BOT_MESSAGE");
        }
        assertThat(jdbc.queryForObject("SELECT body FROM messages WHERE id=?",String.class,message)).isEqualTo("after");
        assertThat(jdbc.queryForObject("SELECT mutation_revision FROM messages WHERE id=?",Long.class,message)).isEqualTo(1);
        var body=(tools.jackson.databind.node.ObjectNode)json.readTree(activity("outbound-activity","root","channel"));
        body.put("type","messageDelete");body.withObjectProperty("from").put("id","28:bot");
        var event=deliveries.persistAndWake(integration,"bot-delete",json.writeValueAsString(body),"bot-delete");worker.process(event.id());worker.process(event.id());
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM messages WHERE id=?",Boolean.class,message)).isTrue();
        assertThat(jdbc.queryForObject("SELECT body FROM message_content_versions WHERE message_id=? AND revision=0",String.class,message)).isEqualTo("before");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM messages",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cases",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM message_mutations",Integer.class)).isZero();
    }

    @Test void rootRepliesAndNewRootsUseSeparateCasesAndTerminalActivityLinksANewGeneration() {
        var first = handler.handle(command("1", "root-1"));
        assertThat(handler.handle(command("2", "root-1")).caseId()).isEqualTo(first.caseId());
        assertThat(handler.handle(command("3", "root-2")).caseId()).isNotEqualTo(first.caseId());
        jdbc.update("UPDATE cases SET status='RESOLVED',resolution_category='SOLVED',resolved_at=CURRENT_TIMESTAMP WHERE id=?", first.caseId());
        var next = handler.handle(command("4", "root-1"));
        assertThat(next.caseId()).isNotEqualTo(first.caseId());
        assertThat(jdbc.queryForObject("SELECT related_case_id FROM cases WHERE id=?", UUID.class, next.caseId())).isEqualTo(first.caseId());
        assertThat(handler.handle(command("1", "root-1")).caseId()).isEqualTo(first.caseId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM messages", Integer.class)).isEqualTo(4);
    }
    @Test void groupChatHasOneActiveCaseAndEditsTargetOriginalMessageWithNullThread() {
        var first = handler.handle(command("1", null));
        assertThat(handler.handle(command("2", null)).caseId()).isEqualTo(first.caseId());
        var original = command("1", null);
        var edit = new Command(original.inboundEventId(), integration, channel, IntegrationProvider.TEAMS,
                "edit-1", "conversation", "1", null, "customer", "edited", Mutation.EDIT,
                Instant.now().plusSeconds(1), "edit-test");
        assertThat(handler.handle(edit).caseId()).isEqualTo(first.caseId());
        assertThat(jdbc.queryForObject("SELECT body FROM messages WHERE id=?", String.class, first.messageId())).isEqualTo("edited");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cases", Integer.class)).isEqualTo(1);
    }
    Command command(String id, String thread) {
        var event = events.persistAuthenticated("TEAMS", integration, id, "{}", "teams-test");
        return new Command(event.id(), integration, channel, IntegrationProvider.TEAMS, id,
                "conversation", id, thread, "customer", "ordinary message without mention", Mutation.CREATE,
                Instant.now(), "teams-test");
    }
    String activity(String id,String thread,String conversationType) {
        return "{\"id\":\""+id+"\",\"type\":\"message\",\"timestamp\":\"2026-11-19T12:00:00Z\",\"channelId\":\"msteams\",\"serviceUrl\":\"https://smba.trafficmanager.net/emea/\",\"from\":{\"id\":\"human\"},\"recipient\":{\"id\":\"28:bot\"},\"conversation\":{\"id\":\"conversation\",\"conversationType\":\""+conversationType+"\"},\"channelData\":{\"team\":{\"aadGroupId\":\""+TEAM+"\"},\"channel\":{\"id\":\"conversation\"}},\"text\":\"ordinary without mention\""+(thread==null ? "" : ",\"replyToId\":\""+thread+"\"")+"}";
    }
    @Test void durableWorkerProcessesRootAndReplyOnceAndSavesExactConnectorThreadRoute() {
        var first=deliveries.persistAndWake(integration,"root",activity("root",null,"channel"),"correlation-1");
        var reply=deliveries.persistAndWake(integration,"reply",activity("reply","root","channel"),"correlation-2");
        assertThat(events.findById(first.id()).orElseThrow().status()).isEqualTo("RECEIVED");
        worker.process(first.id());worker.process(reply.id());worker.process(first.id());
        assertThat(events.findById(first.id()).orElseThrow().status()).isEqualTo("PROCESSED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cases",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM messages",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT connector_conversation_id FROM teams_conversation_routes",String.class)).isEqualTo("conversation");
    }
    @Test void replyChainPayloadWithoutReplyToIdStaysInTheRootCase() {
        var root=deliveries.persistAndWake(integration,"chain-root",activity("root",null,"channel"),"chain-root");
        var json=new tools.jackson.databind.ObjectMapper();
        var body=(tools.jackson.databind.node.ObjectNode)json.readTree(activity("reply",null,"channel"));
        body.withObjectProperty("channelData").withObjectProperty("replyChain").put("id","root");
        var reply=deliveries.persistAndWake(integration,"chain-reply",json.writeValueAsString(body),"chain-reply");
        worker.process(root.id());worker.process(reply.id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cases",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM messages",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT external_thread_key FROM cases",String.class)).isEqualTo("root");
    }
    @Test void ignoredInactiveAndUnapprovedContextsCannotCreateBusinessRecords() {
        jdbc.update("UPDATE channels SET ignored=TRUE WHERE id=?",channel);
        var ignored=deliveries.persistAndWake(integration,"ignored",activity("1",null,"channel"),"ignored");
        worker.process(ignored.id());
        assertThat(jdbc.queryForObject("SELECT processing_outcome FROM inbound_events WHERE id=?",String.class,ignored.id())).isEqualTo("IGNORED_BY_CHANNEL");
        jdbc.update("UPDATE channels SET ignored=FALSE,metadata_json='{}'::jsonb WHERE id=?",channel);
        var unapproved=deliveries.persistAndWake(integration,"unapproved",activity("2",null,"channel"),"unapproved");
        worker.process(unapproved.id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cases",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teams_conversation_routes",Integer.class)).isZero();
    }
    @Test void concurrentRepliesCreateExactlyOneActiveCase() throws Exception {
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var jobs=new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for(int i=0;i<8;i++) {var command=command("concurrent-"+i,"root");jobs.add(pool.submit(()->handler.handle(command)));}
            for(var job:jobs) job.get(30,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cases",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM messages",Integer.class)).isEqualTo(8);
    }
    @Test void deletionRetainsLastAcceptedContentAndCannotBeResurrectedByLaterEdit() {
        var original=command("retained", "root-retained");
        var stored=handler.handle(original);
        handler.handle(new Command(original.inboundEventId(), integration, channel, IntegrationProvider.TEAMS,
                "retained-edit", "conversation", "retained", "root-retained", "customer", "last accepted content",
                Mutation.EDIT, Instant.now().plusSeconds(1), "retained-test"));
        var deletion=new Command(original.inboundEventId(), integration, channel, IntegrationProvider.TEAMS,
                "retained-delete", "conversation", "retained", "root-retained", "customer", null,
                Mutation.DELETE, Instant.now().plusSeconds(2), "retained-test");
        handler.handle(deletion);handler.handle(deletion);
        handler.handle(new Command(original.inboundEventId(), integration, channel, IntegrationProvider.TEAMS,
                "late-edit", "conversation", "retained", "root-retained", "customer", "must not resurrect",
                Mutation.EDIT, Instant.now().plusSeconds(3), "retained-test"));
        assertThat(jdbc.queryForObject("SELECT body FROM messages WHERE id=?",String.class,stored.messageId()))
                .isEqualTo("last accepted content");
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM messages WHERE id=?",Boolean.class,stored.messageId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action='CUSTOMER_MESSAGE_DELETED'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cases",Integer.class)).isEqualTo(1);
    }
    @Test void disabledIntegrationAndPreMonitoringMessagesHaveNoBusinessEffects() {
        jdbc.update("UPDATE integrations SET status='DISABLED' WHERE id=?",integration);
        var disabled=deliveries.persistAndWake(integration,"disabled",activity("1",null,"channel"),"disabled");
        worker.process(disabled.id());
        jdbc.update("UPDATE integrations SET status='ENABLED',created_at='2026-11-20T00:00:00Z' WHERE id=?",integration);
        var historic=deliveries.persistAndWake(integration,"historic",activity("2",null,"channel"),"historic");
        worker.process(historic.id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cases",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM messages",Integer.class)).isZero();
    }
    @Test void imagePdfAndDocxTransferOutsideTheTransactionAndPermissionRepairReusesMetadata() throws Exception {
        var sources=java.util.List.of(new TeamsFilesHttpClient.Source("inline:0","image.png","image/png","INLINE",0),
                new TeamsFilesHttpClient.Source("reference:pdf","evidence.pdf","application/pdf","REFERENCE",-1),
                new TeamsFilesHttpClient.Source("reference:docx","evidence.docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document","REFERENCE",-1));
        when(files.discover(eq(integration),any())).thenAnswer(call->{assertNoTransaction();return sources;});
        var permissionDenied=new java.util.concurrent.atomic.AtomicBoolean(true);
        when(files.download(eq(integration),any(),any(),any())).thenAnswer(call->{
            assertNoTransaction();var source=(TeamsFilesHttpClient.Source)call.getArgument(2);
            if(source.name().endsWith("pdf")&&permissionDenied.get())
                throw com.unifiedsupportinbox.provider.ProviderAttachmentException.authentication("TEAMS_FILE_PERMISSION_REQUIRED");
            String extension=source.name().substring(source.name().lastIndexOf('.')+1);
            var path=java.nio.file.Files.createTempFile("teams-test-","."+extension);
            java.nio.file.Files.copy(java.nio.file.Path.of("src/test/resources/attachments/preview-fixtures/sample."+extension),path,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(path)));
            return new TeamsFilesHttpClient.Download(path,source.name(),source.type(),java.nio.file.Files.size(path),hash);
        });
        var stored=new java.util.HashMap<String,byte[]>();
        doAnswer(call->{assertNoTransaction();stored.put(call.getArgument(0),((java.io.InputStream)call.getArgument(1)).readAllBytes());return null;})
                .when(objects).put(anyString(),any(),anyLong(),anyString());
        when(scanner.scan(any())).thenAnswer(call->{
            assertNoTransaction();UUID id=call.getArgument(0);
            jdbc.update("UPDATE attachments SET scan_status='CLEAN',scan_error=NULL,detected_content_type=content_type WHERE id=?",id);
            return metadata.findById(id).orElseThrow();
        });
        var body=(tools.jackson.databind.node.ObjectNode)new tools.jackson.databind.ObjectMapper().readTree(activity("files-root",null,"channel"));
        body.withArrayProperty("attachments").addObject().put("contentType","text/html").put("content","synthetic files");
        var event=deliveries.persistAndWake(integration,"files",body.toString(),"files-correlation");
        worker.process(event.id());verifyNoInteractions(files);transfers.processNext();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attachments",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attachments WHERE scan_status='CLEAN'",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT error_code FROM teams_attachment_jobs",String.class)).isEqualTo("TEAMS_FILE_PERMISSION_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT next_attempt_at IS NULL FROM teams_attachment_jobs",Boolean.class)).isTrue();
        permissionDenied.set(false);assertThat(transfers.retry(integration)).isEqualTo(1);transfers.processNext();transfers.processNext();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attachments WHERE scan_status='CLEAN'",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT status FROM teams_attachment_jobs",String.class)).isEqualTo("DONE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cases",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM messages",Integer.class)).isEqualTo(1);
        assertThat(stored).hasSize(3);
        // Finished transfer state must not block the 30-day raw-event retention purge.
        jdbc.update("DELETE FROM inbound_events WHERE id=?",event.id());
        assertThat(jdbc.queryForObject("SELECT inbound_event_id IS NULL FROM teams_attachment_jobs",Boolean.class)).isTrue();
        jdbc.update("DELETE FROM attachments WHERE message_id=(SELECT message_id FROM teams_attachment_jobs)");
        jdbc.update("DELETE FROM messages WHERE id=(SELECT message_id FROM teams_attachment_jobs)");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teams_attachment_jobs",Integer.class)).isZero();
    }
    @Test void policyViolationCannotBeReplayedByAdministrativeRepair() {
        var tooMany=new java.util.ArrayList<TeamsFilesHttpClient.Source>();
        for(int i=0;i<11;i++)tooMany.add(new TeamsFilesHttpClient.Source("reference:"+i,"file-"+i+".pdf","application/pdf","REFERENCE",-1));
        when(files.discover(eq(integration),any())).thenReturn(tooMany);
        var body=(tools.jackson.databind.node.ObjectNode)new tools.jackson.databind.ObjectMapper().readTree(activity("limit-root",null,"channel"));
        body.withArrayProperty("attachments").addObject().put("contentType","text/html");
        var event=deliveries.persistAndWake(integration,"limit",body.toString(),"limit-correlation");worker.process(event.id());transfers.processNext();
        assertThat(jdbc.queryForObject("SELECT error_code FROM teams_attachment_jobs",String.class)).isEqualTo("POLICY_ATTACHMENT_COUNT");
        assertThat(transfers.retry(integration)).isZero();verify(files,never()).download(any(),any(),any(),any());
        verifyNoInteractions(objects,scanner);
    }
    static void assertNoTransaction() {
        assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }
    @Test void asynchronousReceiptSurvivesReplayAndIsBoundToTheOriginalProviderContext() {
        var root=handler.handle(command("receipt-root","receipt-root"));
        UUID user=jdbc.queryForObject("INSERT INTO users(email,display_name,role,active) VALUES ('receipt@example.com','Synthetic owner','USER',TRUE) RETURNING id",UUID.class);
        UUID outgoing=jdbc.queryForObject("INSERT INTO messages(case_id,external_thread_key,kind,author_user_id,body,inbound,delivery_status,correlation_id) VALUES (?,'receipt-root','SUPPORT',?,'synthetic reply',FALSE,'SENDING','receipt-test') RETURNING id",UUID.class,root.caseId(),user);
        var send=new com.unifiedsupportinbox.messaging.MessageDeliveryProvider.DeliveryCommand(outgoing,"stable",root.caseId(),IntegrationProvider.TEAMS,integration,"conversation","receipt-root","synthetic reply",com.unifiedsupportinbox.messaging.MessageBodyFormat.PLAIN_TEXT,"receipt-test");
        assertThat(acceptance.accepted(send)).isFalse();acceptance.record(send);acceptance.record(send);
        assertThat(acceptance.accepted(send)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teams_outbound_acceptance",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT http_status FROM teams_outbound_acceptance",Integer.class)).isEqualTo(202);
        assertThat(jdbc.queryForObject("SELECT external_message_id FROM messages WHERE id=?",String.class,outgoing)).isNull();
        var wrong=new com.unifiedsupportinbox.messaging.MessageDeliveryProvider.DeliveryCommand(outgoing,"stable",root.caseId(),IntegrationProvider.TEAMS,integration,"another-channel","receipt-root","synthetic reply",com.unifiedsupportinbox.messaging.MessageBodyFormat.PLAIN_TEXT,"receipt-test");
        assertThat(acceptance.accepted(wrong)).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(()->acceptance.record(wrong)).isInstanceOf(IllegalStateException.class);
    }
}
