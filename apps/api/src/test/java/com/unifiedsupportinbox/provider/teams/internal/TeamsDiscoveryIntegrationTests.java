package com.unifiedsupportinbox.provider.teams.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.unifiedsupportinbox.*;
import com.unifiedsupportinbox.integration.*;
import com.unifiedsupportinbox.provider.internal.ConfiguredProviderSecretResolver;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

@Tag("integration") @ActiveProfiles("test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.MOCK,properties={"usi.providers.teams.client-id=33333333-3333-4333-8333-333333333333","usi.providers.teams.tenant-id=22222222-2222-4222-8222-222222222222"})
class TeamsDiscoveryIntegrationTests {
    static final org.testcontainers.postgresql.PostgreSQLContainer POSTGRES=TestInfrastructure.postgres();
    static final UUID TEAM=UUID.fromString("11111111-1111-4111-8111-111111111111");
    @Autowired JdbcTemplate jdbc; @Autowired ProviderConnectionConfiguration connections;
    @Autowired TeamsConnectionDiscovery discovery; @Autowired TeamsInboundDeliveryService inbound;
    @Autowired TeamsInboundWorker worker; @Autowired ObjectMapper json;
    @MockitoBean TeamsDirectoryClient directory;
    @MockitoBean ConfiguredProviderSecretResolver secrets;
    UUID integration,customer;
    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry r){POSTGRES.start();r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);r.add("spring.datasource.driver-class-name",POSTGRES::getDriverClassName);r.add("spring.flyway.enabled",()->true);r.add("spring.jpa.hibernate.ddl-auto",()->"validate");}
    @AfterAll static void stop(){POSTGRES.stop();}
    @BeforeEach void fixture(){
        TestInfrastructure.resetPostgres(POSTGRES);
        integration=jdbc.queryForObject("INSERT INTO integrations(provider,display_name,status,health,secret_ref,created_at) VALUES ('TEAMS','Synthetic discovery','ENABLED','HEALTHY','synthetic/teams',CURRENT_TIMESTAMP-INTERVAL '1 day') RETURNING id",UUID.class);
        customer=jdbc.queryForObject("INSERT INTO customers(name) VALUES ('Default customer') RETURNING id",UUID.class);
        connections.selectTeam(integration,TEAM,customer);
        when(secrets.resolve(anyString(),eq("teams-client-secret"))).thenAnswer(c->Optional.of("synthetic-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        when(directory.channels(integration,TEAM)).thenAnswer(c->{assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return List.of(json.readTree("{\"id\":\"new-channel\",\"displayName\":\"Support\",\"membershipType\":\"standard\"}"),json.readTree("{\"id\":\"private\",\"displayName\":\"Private\",\"membershipType\":\"private\"}"));});
    }
    void scan(){var claim=connections.claimDue().orElseThrow();int count=discovery.discover(claim);connections.finished(claim,"READY",null,count,Duration.ofSeconds(30));}
    String activity(String id,String type){return "{\"id\":\""+id+"\",\"type\":\""+type+"\",\"channelId\":\"msteams\",\"timestamp\":\""+Instant.now()+"\",\"serviceUrl\":\"https://smba.trafficmanager.net/emea/\",\"from\":{\"id\":\"human\"},\"recipient\":{\"id\":\"28:33333333-3333-4333-8333-333333333333\"},\"conversation\":{\"id\":\"new-channel;messageid=root\",\"conversationType\":\"channel\"},\"channelData\":{\"team\":{\"aadGroupId\":\""+TEAM+"\"},\"channel\":{\"id\":\"new-channel\"},\"replyChain\":{\"id\":\"root\"}},\"text\":\"immediate message\"}";}
    @Test void firstMessageBeforeDiscoveryIsRetriedThenCreatesOneMappedCaseWithTheCorrectReplyRoute(){
        var event=inbound.persistTeamAndWake(integration,TEAM,"immediate",activity("root","message"),"immediate");worker.process(event.id());
        assertThat(jdbc.queryForObject("SELECT status FROM inbound_events WHERE id=?",String.class,event.id())).isEqualTo("FAILED");
        scan();worker.process(event.id());worker.process(event.id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cases WHERE customer_id=?",Integer.class,customer)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM messages",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT connector_conversation_id FROM teams_conversation_routes WHERE thread_key='root'",String.class)).isEqualTo("new-channel;messageid=root");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM channels",Integer.class)).isEqualTo(1);
        assertThat(connections.settings(integration).discoveredChannels()).isEqualTo(1);
    }
    @Test void periodicReconciliationAfterRestartAddsStandardChannelsAndPreservesIndividualSettings(){
        scan();UUID custom=jdbc.queryForObject("INSERT INTO customers(name) VALUES ('Custom') RETURNING id",UUID.class);
        jdbc.update("UPDATE channels SET customer_id=?,ignored=TRUE,metadata_json=metadata_json||'{\"siteId\":\"retained\"}'::jsonb",custom);
        jdbc.update("UPDATE integration_connection_settings SET next_discovery_at=CURRENT_TIMESTAMP-INTERVAL '1 hour'");
        scan();assertThat(jdbc.queryForObject("SELECT customer_id FROM channels",UUID.class)).isEqualTo(custom);
        assertThat(jdbc.queryForObject("SELECT ignored FROM channels",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT metadata_json->>'siteId' FROM channels",String.class)).isEqualTo("retained");
        verify(directory,times(2)).channels(integration,TEAM);
    }
    @Test void channelCreatedRequestsDiscoveryWithoutCreatingBusinessContent(){
        scan();var body=(tools.jackson.databind.node.ObjectNode)json.readTree(activity("created","conversationUpdate"));
        body.withObjectProperty("channelData").put("eventType","channelCreated");
        var event=inbound.persistTeamAndWake(integration,TEAM,"created",json.writeValueAsString(body),"created");worker.process(event.id());
        assertThat(jdbc.queryForObject("SELECT discovery_requested FROM integration_connection_settings",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cases",Integer.class)).isZero();scan();
    }
    @Test void draftSavedBeforeVpsSetupAdoptsOnlyTheFirstConfiguredTenantWithoutEnabling(){
        jdbc.update("UPDATE integrations SET status='CONFIGURING',workspace_external_id='teams-unconfigured' WHERE id=?",integration);
        scan();
        assertThat(jdbc.queryForObject("SELECT workspace_external_id FROM integrations WHERE id=?",String.class,integration)).isEqualTo("22222222-2222-4222-8222-222222222222");
        assertThat(jdbc.queryForObject("SELECT status FROM integrations WHERE id=?",String.class,integration)).isEqualTo("CONFIGURING");
        connections.adoptConfiguredTeamsTenant(integration,UUID.randomUUID().toString());
        assertThat(jdbc.queryForObject("SELECT workspace_external_id FROM integrations WHERE id=?",String.class,integration)).isEqualTo("22222222-2222-4222-8222-222222222222");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action='TEAMS_SERVER_IDENTITY_BOUND'",Integer.class)).isEqualTo(1);
    }
}
