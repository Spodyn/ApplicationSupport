package com.unifiedsupportinbox.provider.slack.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
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

@Tag("integration") @ActiveProfiles("test") @SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.MOCK)
class SlackDiscoveryIntegrationTests {
    static final org.testcontainers.postgresql.PostgreSQLContainer POSTGRES=TestInfrastructure.postgres();
    @Autowired JdbcTemplate jdbc; @Autowired ProviderConnectionConfiguration connections;
    @Autowired SlackConnectionDiscovery discovery; @Autowired SlackInboundDeliveryService inbound; @Autowired SlackInboundWorker worker;
    @MockitoBean SlackAdministrationApiClient slack;
    @MockitoBean ConfiguredProviderSecretResolver secrets;
    UUID integration;
    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry r){POSTGRES.start();r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);r.add("spring.datasource.password",POSTGRES::getPassword);r.add("spring.datasource.driver-class-name",POSTGRES::getDriverClassName);r.add("spring.flyway.enabled",()->true);r.add("spring.jpa.hibernate.ddl-auto",()->"validate");}
    @AfterAll static void stop(){POSTGRES.stop();}
    @BeforeEach void fixture(){
        TestInfrastructure.resetPostgres(POSTGRES);
        integration=jdbc.queryForObject("INSERT INTO integrations(provider,display_name,status,health,workspace_external_id,secret_ref) VALUES ('SLACK','Synthetic discovery','ENABLED','HEALTHY','T-SYNTHETIC','synthetic/slack') RETURNING id",UUID.class);
        when(secrets.resolve(anyString(),anyString())).thenAnswer(c->Optional.of("synthetic-token".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        when(slack.testAuthentication(any())).thenReturn(new SlackAdministrationApiClient.AuthResponse(200,true,"T-SYNTHETIC","Synthetic",null));
        when(slack.listConversations(any(),eq("T-SYNTHETIC"),isNull())).thenAnswer(c->{assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return new SlackAdministrationApiClient.ConversationsResponse(200,true,List.of(new SlackAdministrationApiClient.Conversation("C-NOT-MEMBER","not-member",false,false,false)),"next",null);});
        when(slack.listConversations(any(),eq("T-SYNTHETIC"),eq("next"))).thenReturn(new SlackAdministrationApiClient.ConversationsResponse(200,true,List.of(new SlackAdministrationApiClient.Conversation("C-NEW","new-channel",true,false,false)),null,null));
    }
    void scan(){var claim=connections.claimDue().orElseThrow();int count=discovery.discover(claim);connections.finished(claim,"READY",null,count,Duration.ofSeconds(30));}
    @Test void immediateMessageWaitsForPaginatedDiscoveryThenCreatesOneUnassignedUnreadCase(){
        jdbc.update("INSERT INTO users(email,display_name,role,active) VALUES ('reader@example.test','Reader','USER',TRUE)");
        String payload="{\"type\":\"event_callback\",\"event_id\":\"Ev-immediate\",\"event\":{\"type\":\"message\",\"channel_type\":\"channel\",\"channel\":\"C-NEW\",\"user\":\"human\",\"text\":\"first message\",\"ts\":\""+Instant.now().getEpochSecond()+".000001\"}}";
        var event=inbound.persistAndWake(integration,"Ev-immediate",payload,"immediate");
        worker.process(event.id());assertThat(jdbc.queryForObject("SELECT status FROM inbound_events WHERE id=?",String.class,event.id())).isEqualTo("FAILED");
        scan();worker.process(event.id());worker.process(event.id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cases WHERE customer_id IS NULL",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM case_read_states",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM messages",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM channels",Integer.class)).isEqualTo(1);
    }
    @Test void rediscoveryPreservesMappedAndIgnoredChannel(){
        scan();UUID customer=jdbc.queryForObject("INSERT INTO customers(name) VALUES ('Mapped') RETURNING id",UUID.class);
        jdbc.update("UPDATE channels SET customer_id=?,ignored=TRUE",customer);connections.requestDiscovery(integration);scan();
        assertThat(jdbc.queryForObject("SELECT customer_id FROM channels",UUID.class)).isEqualTo(customer);
        assertThat(jdbc.queryForObject("SELECT ignored FROM channels",Boolean.class)).isTrue();
    }
}
