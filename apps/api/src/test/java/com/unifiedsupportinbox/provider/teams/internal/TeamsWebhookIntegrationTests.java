package com.unifiedsupportinbox.provider.teams.internal;

import static org.assertj.core.api.Assertions.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.*;
import com.unifiedsupportinbox.UsiApiApplication;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("integration")
class TeamsWebhookIntegrationTests {
    static final org.testcontainers.postgresql.PostgreSQLContainer POSTGRES=TestInfrastructure.postgres();
    static ConfigurableApplicationContext context;
    static JdbcTemplate jdbc;
    static URI endpoint;
    static final HttpClient HTTP=HttpClient.newHttpClient();
    static final String CLIENT=TeamsSecurityTests.CLIENT, TENANT=TeamsSecurityTests.TENANT, TEAM="11111111-1111-4111-8111-111111111111";
    @BeforeAll static void start() {
        POSTGRES.start();
        context=new SpringApplicationBuilder(UsiApiApplication.class,SyntheticKeys.class).profiles("test")
                .run("--server.port=0","--server.address=127.0.0.1","--spring.datasource.url="+POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username="+POSTGRES.getUsername(),"--spring.datasource.password="+POSTGRES.getPassword(),
                        "--spring.datasource.driver-class-name="+POSTGRES.getDriverClassName(),"--spring.flyway.enabled=true","--spring.jpa.hibernate.ddl-auto=validate");
        jdbc=context.getBean(JdbcTemplate.class);
        endpoint=URI.create("http://127.0.0.1:"+context.getEnvironment().getProperty("local.server.port")+"/api/v1/provider-callbacks/teams");
    }
    @AfterAll static void stop() {if(context!=null) context.close();POSTGRES.stop();}
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    static class SyntheticKeys {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        TeamsJwtAuthenticator syntheticAuthenticator() {
            return new TeamsJwtAuthenticator(CLIENT,TENANT,()->new TeamsJwtAuthenticator.SigningKeys(
                    new JWKSet(TeamsSecurityTests.KEY.toPublicJWK()),Map.of()),Clock.systemUTC());
        }
    }
    @BeforeEach void fixture() {
        TestInfrastructure.resetPostgres(POSTGRES);
        jdbc.update("INSERT INTO integrations(provider,display_name,status,health,workspace_external_id,secret_ref) VALUES ('TEAMS','Synthetic Teams','ENABLED','UNKNOWN',?,'synthetic/teams')",TENANT);
        jdbc.update("INSERT INTO teams_instance_binding(integration_id,team_id) SELECT id,?::uuid FROM integrations WHERE provider='TEAMS'",TEAM);
    }
    String payload(String id,String type,String conversation) {
        return "{\"id\":\""+id+"\",\"type\":\""+type+"\",\"channelId\":\"msteams\",\"timestamp\":\"2026-11-19T12:00:00Z\",\"serviceUrl\":\"https://smba.trafficmanager.net/emea/\",\"recipient\":{\"id\":\"28:"+CLIENT+"\"},\"channelData\":{\"team\":{\"aadGroupId\":\""+TEAM+"\"},\"tenant\":{\"id\":\""+TENANT+"\"}},\"conversation\":{\"id\":\""+conversation+"\"}}";
    }
    String token() throws Exception {
        var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(TeamsSecurityTests.KEY.getKeyID()).build(),
                new JWTClaimsSet.Builder().issuer("https://api.botframework.com").audience(CLIENT)
                        .expirationTime(Date.from(Instant.now().plusSeconds(300))).notBeforeTime(Date.from(Instant.now().minusSeconds(60)))
                        .claim("serviceurl","https://smba.trafficmanager.net/emea/").build());
        jwt.sign(new RSASSASigner(TeamsSecurityTests.KEY));return "Bearer "+jwt.serialize();
    }
    HttpResponse<String> post(String body,String token) throws Exception {
        var request=HttpRequest.newBuilder(endpoint).header("Content-Type","application/json");
        if(token!=null) request.header("Authorization",token);
        return HTTP.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void signedIntakeDeduplicatesRetriesButNotOtherConversationsOrEdits() throws Exception {
        String body=payload("1","message","chat-1");
        assertThat(post(body,token()).statusCode()).isEqualTo(200);
        assertThat(post(body,token()).statusCode()).isEqualTo(200);
        assertThat(post(payload("1","message","chat-2"),token()).statusCode()).isEqualTo(200);
        assertThat(post(payload("1","messageUpdate","chat-1"),token()).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inbound_events",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE type='teams.inbound.received'",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cases",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inbound_events WHERE correlation_id IS NOT NULL AND status='RECEIVED'",Integer.class)).isEqualTo(3);
    }
    @Test void anonymousForgedWrongTenantAndMalformedBodiesCannotCreateDurableEvents() throws Exception {
        String body=payload("1","message","chat-1");
        assertThat(post(body,null).statusCode()).isEqualTo(401);
        assertThat(post(body,"Bearer forged").statusCode()).isEqualTo(401);
        assertThat(post(body.replace(TENANT,"33333333-3333-4333-8333-333333333333"),token()).statusCode()).isEqualTo(401);
        assertThat(post(body.replace(TEAM,"44444444-4444-4444-8444-444444444444"),token()).statusCode()).isEqualTo(401);
        assertThat(post("{",token()).statusCode()).isEqualTo(400);
        assertThat(post(body.replace("\"id\":\"1\",",""),token()).statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inbound_events",Integer.class)).isZero();
    }
    @Test void selectedTeamChangeBetweenVerificationAndPersistenceCannotStoreAnEvent() {
        UUID integration=jdbc.queryForObject("SELECT integration_id FROM teams_instance_binding",UUID.class);
        jdbc.update("UPDATE teams_instance_binding SET team_id=?::uuid",UUID.randomUUID().toString());
        assertThatThrownBy(()->context.getBean(TeamsInboundDeliveryService.class).persistTeamAndWake(integration,UUID.fromString(TEAM),"stale",payload("stale","message","channel"),"stale"))
                .isInstanceOf(com.unifiedsupportinbox.ApiProblemException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inbound_events",Integer.class)).isZero();
    }
}
