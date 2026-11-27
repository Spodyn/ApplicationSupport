package com.unifiedsupportinbox.integration.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.unifiedsupportinbox.*;
import com.unifiedsupportinbox.integration.*;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import java.time.*;
import java.util.*;
import java.util.zip.*;
import java.io.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Tag("integration") @SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.MOCK) @ActiveProfiles("test")
class ConnectionOnboardingIntegrationTests {
    static final org.testcontainers.postgresql.PostgreSQLContainer POSTGRES=TestInfrastructure.postgres();
    static final String TEAM="11111111-1111-4111-8111-111111111111",TENANT="22222222-2222-4222-8222-222222222222",CLIENT="33333333-3333-4333-8333-333333333333";
    @Autowired JdbcTemplate jdbc;
    @Autowired ConnectionOnboardingController controller;
    @Autowired ProviderConnectionConfiguration connections;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean(name="teamsConnectionDiscovery") ProviderConnectionRuntime teams;
    UUID customer;UsernamePasswordAuthenticationToken admin;
    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry r) {
        POSTGRES.start();r.add("spring.datasource.url",POSTGRES::getJdbcUrl);r.add("spring.datasource.username",POSTGRES::getUsername);
        r.add("spring.datasource.password",POSTGRES::getPassword);r.add("spring.datasource.driver-class-name",POSTGRES::getDriverClassName);
        r.add("spring.flyway.enabled",()->true);r.add("spring.jpa.hibernate.ddl-auto",()->"validate");
    }
    @AfterAll static void stop(){POSTGRES.stop();}
    @BeforeEach void fixture() {
        TestInfrastructure.resetPostgres(POSTGRES);
        when(teams.provider()).thenReturn(IntegrationProvider.TEAMS);
        when(teams.runtime(any())).thenReturn(new ProviderConnectionRuntime.Runtime("https://instance.example.test/api/v1/provider-callbacks/teams",CLIENT,TENANT,"teams/synthetic",true));
        customer=jdbc.queryForObject("INSERT INTO customers(name) VALUES ('Default customer') RETURNING id",UUID.class);
        UUID user=jdbc.queryForObject("INSERT INTO users(email,display_name,role,active) VALUES ('setup@example.test','Setup Admin','ADMIN',TRUE) RETURNING id",UUID.class);
        admin=new UsernamePasswordAuthenticationToken(user.toString(),"",List.of(new SimpleGrantedAuthority("ROLE_ADMIN"),new SimpleGrantedAuthority("manage_integrations")));
    }
    IntegrationView create(){return controller.createTeams(new ConnectionOnboardingController.TeamsInput("Support team","https://teams.microsoft.com/l/team/19%3Aopaque%40thread.tacv2/conversations?groupId="+TEAM+"&tenantId="+TENANT,customer,true),admin);}
    @Test void panelSavePersistsSingleSelectedTeamDefaultsAndAutomaticDiscoveryWithoutSecrets() {
        var created=create();var view=controller.get(created.id(),admin);
        assertThat(view.teamId()).isEqualTo(UUID.fromString(TEAM));assertThat(view.defaultCustomerId()).isEqualTo(customer);
        assertThat(view.automaticDiscovery()).isTrue();assertThat(view.callbackUrl()).startsWith("https://instance.example.test/");
        assertThat(created.workspaceExternalId()).isEqualTo(TENANT);
        assertThat(new ObjectMapper().writeValueAsString(view)).doesNotContain("teams/synthetic","client-secret","token");
        assertThatThrownBy(()->controller.createTeams(new ConnectionOnboardingController.TeamsInput("Another",UUID.randomUUID().toString(),customer,true),admin)).isInstanceOf(ApiProblemException.class);
        controller.configure(created.id(),new ConnectionOnboardingController.DiscoveryInput(false),admin);
        assertThat(controller.get(created.id(),admin).automaticDiscovery()).isFalse();
    }
    @Test void schedulerLeaseSurvivesRestartAndExpiredWorkersCannotFinishANewerClaim() {
        var integration=create();connections.configure(integration.id(),false);connections.requestDiscovery(integration.id());var first=connections.claimDue().orElseThrow();assertThat(first.integrationId()).isEqualTo(integration.id());
        assertThat(connections.claimDue()).isEmpty();
        jdbc.update("UPDATE integration_connection_settings SET lease_until=CURRENT_TIMESTAMP-INTERVAL '1 second'");
        // A crashed worker remains eligible even after its request flag was consumed.
        var restarted=connections.claimDue().orElseThrow();
        assertThat(restarted.lease()).isNotEqualTo(first.lease());
        connections.finished(first,"READY",null,99,Duration.ofSeconds(30));
        assertThat(connections.settings(integration.id()).discoveredChannels()).isZero();
        connections.finished(restarted,"READY",null,2,Duration.ofSeconds(30));assertThat(connections.settings(integration.id()).discoveredChannels()).isEqualTo(2);
    }
    @Test void authorizationAndMissingBotStateAreExplicit() {
        var created=create();var ordinary=new UsernamePasswordAuthenticationToken("ordinary","",List.of(new SimpleGrantedAuthority("ROLE_USER")));
        assertThatThrownBy(()->controller.get(created.id(),ordinary)).isInstanceOf(ApiProblemException.class);
        when(teams.runtime(any())).thenReturn(new ProviderConnectionRuntime.Runtime("https://instance.example.test/api/v1/provider-callbacks/teams","","","teams/synthetic",false));
        assertThat(controller.get(created.id(),admin).state()).isEqualTo("MISSING_BOT_CONFIGURATION");
        assertThatThrownBy(()->controller.installationPackage(admin)).isInstanceOf(ApiProblemException.class);
    }
    @Test void manualDiscoveryRetriesAfterRateLimitAndNewRequestsCannotBypassCooldown() {
        var integration=create();connections.configure(integration.id(),false);connections.requestDiscovery(integration.id());
        var claim=connections.claimDue().orElseThrow();
        connections.finished(claim,"ERROR","TEAMS_DISCOVERY_HTTP_429",0,Duration.ofHours(2));
        connections.requestDiscovery(integration.id());assertThat(connections.claimDue()).isEmpty();
        jdbc.update("UPDATE integration_connection_settings SET retry_not_before=CURRENT_TIMESTAMP-INTERVAL '1 second'");
        assertThat(connections.claimDue()).isPresent();
    }
    @Test void installationZipContainsOnlyInstanceManifestAndValidSizedIcons()throws Exception {
        var zip=controller.installationPackage(admin).getBody().getInputStream();Map<String,byte[]> contents=new HashMap<>();
        try(var archive=new ZipInputStream(zip)){for(var entry=archive.getNextEntry();entry!=null;entry=archive.getNextEntry())contents.put(entry.getName(),archive.readAllBytes());}
        assertThat(contents.keySet()).containsExactlyInAnyOrder("manifest.json","color.png","outline.png");
        var manifest=new ObjectMapper().readTree(contents.get("manifest.json"));
        // Current Teams upload validation requires channel readiness on schema 1.25+.
        assertThat(manifest.path("manifestVersion").asText()).isEqualTo("1.25");
        assertThat(manifest.path("$schema").asText()).isEqualTo("https://developer.microsoft.com/json-schemas/teams/v1.25/MicrosoftTeams.schema.json");
        assertThat(manifest.path("supportsChannelFeatures").asText()).isEqualTo("tier1");
        assertThat(manifest.has("supportedChannelTypes")).isFalse();
        assertThat(manifest.path("version").asText()).isEqualTo("1.1.1");
        assertThat(manifest.path("id").asText()).isEqualTo(CLIENT);
        assertThat(manifest.path("description").path("full").asText()).contains("standard channels", "Private and shared channels are not processed");
        assertThat(manifest.path("bots").get(0).path("scopes").toString()).isEqualTo("[\"team\"]");
        assertThat(manifest.path("authorization").path("permissions").path("resourceSpecific")).hasSize(2);
        assertThat(manifest.path("authorization").path("permissions").path("resourceSpecific").toString()).contains("ChannelMessage.Read.Group","ChannelSettings.Read.Group").doesNotContain(".Read.All","ChatMessage.Read.Chat");
        assertThat(manifest.path("webApplicationInfo").path("id").asText()).isEqualTo(CLIENT);
        for(var item:Map.of("color.png",192,"outline.png",32).entrySet()) {
            var bytes=java.nio.ByteBuffer.wrap(contents.get(item.getKey()));assertThat(bytes.getInt(16)).isEqualTo(item.getValue());assertThat(bytes.getInt(20)).isEqualTo(item.getValue());
        }
    }
}
