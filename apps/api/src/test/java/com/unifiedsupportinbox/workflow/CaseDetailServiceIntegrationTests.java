package com.unifiedsupportinbox.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.UsiApiApplication;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
class CaseDetailServiceIntegrationTests {
    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();
    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static CaseDetailService details;

    @BeforeAll
    static void start() {
        POSTGRES.start();
        context = new SpringApplicationBuilder(UsiApiApplication.class).profiles("test").run(
                "--server.port=0", "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.datasource.driver-class-name=" + POSTGRES.getDriverClassName(),
                "--spring.flyway.enabled=true", "--spring.jpa.hibernate.ddl-auto=validate",
                "--spring.session.jdbc.initialize-schema=never", "--usi.bootstrap-admin.enabled=false");
        jdbc = context.getBean(JdbcTemplate.class);
        details = context.getBean(CaseDetailService.class);
    }

    @AfterAll
    static void stop() {
        if (context != null) context.close();
        POSTGRES.stop();
    }

    @Test
    void projectsHeaderPersonalStateRelatedCaseAndServerCalculatedActionsWithoutProviderSecrets() {
        UUID agent = user("USER");
        UUID admin = user("ADMIN");
        UUID related = caseId("related-" + UUID.randomUUID());
        UUID caseId = caseId("detail-" + UUID.randomUUID());
        jdbc.update("UPDATE cases SET related_case_id = ? WHERE id = ?", related, caseId);
        UUID messageId = jdbc.queryForObject("""
                INSERT INTO messages (case_id, external_message_id, kind, author_external_id, author_name, body,
                                      body_format, inbound, provider_created_at, correlation_id)
                VALUES (?, ?, 'CUSTOMER', 'customer', 'Customer', 'hello', 'PLAIN_TEXT', TRUE,
                        CURRENT_TIMESTAMP, 'case-detail-test')
                RETURNING id
                """, UUID.class, caseId, "message-" + UUID.randomUUID());
        jdbc.update("INSERT INTO case_read_states (user_id, case_id, last_read_message_id, last_read_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)",
                agent, caseId, messageId);
        jdbc.update("INSERT INTO case_snoozes (case_id, user_id, until_at) VALUES (?, ?, CURRENT_TIMESTAMP + INTERVAL '1 hour')",
                caseId, agent);
        jdbc.update("INSERT INTO case_ignore_votes (case_id, user_id, weight) VALUES (?, ?, 1)", caseId, admin);

        var detail = details.detail(caseId, agent);

        assertThat(detail.customer().name()).startsWith("Customer detail-");
        assertThat(detail.channel().groupingStrategy()).isEqualTo("SLACK_ROOT_THREAD");
        assertThat(detail.integration().provider()).isEqualTo("SLACK");
        assertThat(detail.integration().displayName()).isEqualTo("Support Slack");
        assertThat(detail.integration()).hasNoNullFieldsOrPropertiesExcept("workspaceExternalId", "workspaceName");
        assertThat(detail.relatedCase().id()).isEqualTo(related);
        assertThat(detail.personalState().lastReadMessageId()).isEqualTo(messageId);
        assertThat(detail.personalState().snoozedUntil()).isNotNull();
        assertThat(detail.ignoreScore()).isEqualTo(1);
        assertThat(detail.availableActions()).contains("CLAIM", "IGNORE", "MARK_READ", "SNOOZE");

        var administratorView = details.detail(caseId, admin);
        assertThat(administratorView.availableActions()).contains("ASSIGN", "FORCE_RESOLVE");
    }

    @Test
    void rejectsMissingCaseAndUnknownAuthenticatedUser() {
        assertThatThrownBy(() -> details.detail(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(ApiProblemException.class);
    }

    private static UUID user(String role) {
        return jdbc.queryForObject("""
                INSERT INTO users (email, display_name, role, active)
                VALUES (?, 'Agent', ?, TRUE) RETURNING id
                """, UUID.class, UUID.randomUUID() + "@example.test", role);
    }

    private static UUID caseId(String key) {
        UUID customer = jdbc.queryForObject("INSERT INTO customers (name, external_ref) VALUES (?, ?) RETURNING id",
                UUID.class, "Customer " + key, key);
        UUID integration = jdbc.queryForObject("""
                INSERT INTO integrations (provider, display_name, status, health, workspace_external_id)
                VALUES ('SLACK', 'Support Slack', 'ENABLED', 'HEALTHY', ?) RETURNING id
                """, UUID.class, key);
        UUID channel = jdbc.queryForObject("""
                INSERT INTO channels (integration_id, external_channel_id, name, customer_id, ignored, grouping_strategy, active)
                VALUES (?, ?, 'support', ?, FALSE, 'SLACK_ROOT_THREAD', TRUE) RETURNING id
                """, UUID.class, integration, key, customer);
        return jdbc.queryForObject("""
                INSERT INTO cases (customer_id, integration_id, channel_id, provider, external_conversation_id)
                VALUES (?, ?, ?, 'SLACK', ?) RETURNING id
                """, UUID.class, customer, integration, channel, key);
    }
}
