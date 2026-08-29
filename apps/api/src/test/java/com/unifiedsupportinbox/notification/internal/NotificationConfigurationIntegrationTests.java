package com.unifiedsupportinbox.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.UsiApiApplication;
import com.unifiedsupportinbox.integration.IntegrationProvider;
import com.unifiedsupportinbox.notification.NotificationRoutingCatalog;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
class NotificationConfigurationIntegrationTests {

    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static NotificationService service;
    private static NotificationRoutingCatalog routes;

    private final Authentication notificationAdmin = UsernamePasswordAuthenticationToken.authenticated(
            "notification-admin@example.invalid",
            "n/a",
            List.of(new SimpleGrantedAuthority("manage_notifications")));

    @BeforeAll
    static void startApplication() {
        POSTGRES.start();
        context = new SpringApplicationBuilder(UsiApiApplication.class)
                .profiles("test")
                .run(
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--spring.datasource.driver-class-name=" + POSTGRES.getDriverClassName(),
                        "--spring.flyway.enabled=true",
                        "--spring.jpa.hibernate.ddl-auto=validate",
                        "--spring.session.jdbc.initialize-schema=never",
                        "--usi.bootstrap-admin.enabled=false");
        jdbc = context.getBean(JdbcTemplate.class);
        service = context.getBean(NotificationService.class);
        routes = context.getBean(NotificationRoutingCatalog.class);
    }

    @AfterAll
    static void stopApplication() {
        if (context != null) context.close();
        POSTGRES.stop();
    }

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM notification_configuration_changes");
        jdbc.update("DELETE FROM notification_rules");
        jdbc.update("DELETE FROM notification_destinations");
        jdbc.update("DELETE FROM channels");
        jdbc.update("DELETE FROM integrations");
    }

    @Test
    void destinationAndRulePersistWithRedactedSecretsAndEnabledRouting() {
        UUID integrationId = createIntegration("SLACK");

        var destination = service.createDestination(notificationAdmin, new NotificationService.DestinationInput(
                "L1 escalation",
                IntegrationProvider.SLACK,
                integrationId,
                "C-SUPPORT-ALERTS",
                true,
                "notification/slack-primary",
                "notification/config-primary"));
        var rule = service.createRule(notificationAdmin, new NotificationService.RuleInput(
                destination.id(),
                "SLA alerts",
                true,
                List.of("sla_warning", "sla_breached"),
                List.of("warning", "critical")));

        assertThat(destination.secretConfigured()).isTrue();
        assertThat(destination.configConfigured()).isTrue();
        assertThat(destination.toString()).doesNotContain("notification/slack-primary", "notification/config-primary");
        assertThat(rule.eventTypes()).containsExactly("sla_warning", "sla_breached");
        assertThat(routes.listEnabledRoutes()).singleElement().satisfies(route -> {
            assertThat(route.destinationId()).isEqualTo(destination.id());
            assertThat(route.ruleId()).isEqualTo(rule.id());
            assertThat(route.targetRef()).isEqualTo("C-SUPPORT-ALERTS");
        });

        assertThat(jdbc.queryForObject(
                "SELECT secret_ref FROM notification_destinations WHERE id = ?",
                String.class,
                destination.id())).isEqualTo("notification/slack-primary");
        assertThat(jdbc.queryForObject(
                "SELECT snapshot_json::text FROM notification_configuration_changes WHERE entity_type = 'DESTINATION'",
                String.class))
                .contains("secretConfigured")
                .doesNotContain("notification/slack-primary", "notification/config-primary");
    }

    @Test
    void disabledDestinationOrRuleIsExcludedFromWorkerRoutingCatalog() {
        UUID integrationId = createIntegration("TEAMS");
        var destination = service.createDestination(notificationAdmin, new NotificationService.DestinationInput(
                "Operations", IntegrationProvider.TEAMS, integrationId, "19:ops", true, null, null));
        var rule = service.createRule(notificationAdmin, new NotificationService.RuleInput(
                destination.id(), "Disconnects", true, List.of("integration_disconnected"), List.of()));

        assertThat(routes.listEnabledRoutes()).hasSize(1);

        var disabled = service.setDestinationEnabled(notificationAdmin, destination.id(), destination.version(), false);
        assertThat(disabled.enabled()).isFalse();
        assertThat(routes.listEnabledRoutes()).isEmpty();

        var enabledAgain = service.setDestinationEnabled(notificationAdmin, destination.id(), disabled.version(), true);
        service.updateRule(notificationAdmin, rule.id(), rule.version(), new NotificationService.RuleInput(
                destination.id(), rule.name(), false, rule.eventTypes(), rule.severityFilters()));
        assertThat(enabledAgain.enabled()).isTrue();
        assertThat(routes.listEnabledRoutes()).isEmpty();
    }

    @Test
    void providerMismatchInvalidFiltersAndStaleVersionAreRejected() {
        UUID slackIntegration = createIntegration("SLACK");

        assertThatThrownBy(() -> service.createDestination(notificationAdmin, new NotificationService.DestinationInput(
                "Wrong provider", IntegrationProvider.TEAMS, slackIntegration, "target", true, null, null)))
                .isInstanceOf(ApiProblemException.class)
                .hasMessageContaining("provider must match");

        var destination = service.createDestination(notificationAdmin, new NotificationService.DestinationInput(
                "Valid", IntegrationProvider.SLACK, slackIntegration, "C1", true, null, null));

        assertThatThrownBy(() -> service.createRule(notificationAdmin, new NotificationService.RuleInput(
                destination.id(), "Invalid", true, List.of("contains whitespace"), List.of())))
                .isInstanceOf(ApiProblemException.class);

        var updated = service.setDestinationEnabled(notificationAdmin, destination.id(), destination.version(), false);
        assertThat(updated.version()).isEqualTo(destination.version() + 1);
        assertThatThrownBy(() -> service.setDestinationEnabled(
                notificationAdmin, destination.id(), destination.version(), true))
                .isInstanceOf(ApiProblemException.class)
                .hasMessageContaining("changed since it was loaded");
    }

    @Test
    void everyConfigurationMutationAppendsActorAndRedactedSnapshot() {
        UUID integrationId = createIntegration("TELEGRAM");
        var destination = service.createDestination(notificationAdmin, new NotificationService.DestinationInput(
                "Leads", IntegrationProvider.TELEGRAM, integrationId, "@support_leads", true,
                "notification/telegram", null));
        var updated = service.setDestinationEnabled(notificationAdmin, destination.id(), destination.version(), false);
        service.deleteDestination(notificationAdmin, destination.id(), updated.version());

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notification_configuration_changes WHERE entity_id = ?",
                Integer.class,
                destination.id())).isEqualTo(3);
        assertThat(jdbc.queryForList(
                "SELECT action FROM notification_configuration_changes WHERE entity_id = ? ORDER BY created_at, id",
                String.class,
                destination.id())).containsExactly("CREATED", "UPDATED", "DELETED");
        assertThat(jdbc.queryForList(
                "SELECT DISTINCT actor_ref FROM notification_configuration_changes WHERE entity_id = ?",
                String.class,
                destination.id())).containsExactly("notification-admin@example.invalid");
        assertThat(jdbc.queryForList(
                "SELECT snapshot_json::text FROM notification_configuration_changes WHERE entity_id = ?",
                String.class,
                destination.id())).allSatisfy(snapshot ->
                assertThat(snapshot).doesNotContain("notification/telegram"));
    }

    private static UUID createIntegration(String provider) {
        return jdbc.queryForObject("""
                INSERT INTO integrations (provider, display_name, status, health, config_json)
                VALUES (?, ?, 'ENABLED', 'HEALTHY', '{}'::jsonb)
                RETURNING id
                """, UUID.class, provider, provider + " notifications");
    }
}
