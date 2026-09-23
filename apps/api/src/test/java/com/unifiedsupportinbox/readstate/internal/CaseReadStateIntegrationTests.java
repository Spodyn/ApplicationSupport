package com.unifiedsupportinbox.readstate.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unifiedsupportinbox.UsiApiApplication;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
class CaseReadStateIntegrationTests {

    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static CaseReadStateRepository repository;

    @BeforeAll
    static void startApplication() {
        POSTGRES.start();
        context = new SpringApplicationBuilder(UsiApiApplication.class)
                .profiles("test")
                .run(
                        "--server.port=0",
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--spring.datasource.driver-class-name=" + POSTGRES.getDriverClassName(),
                        "--spring.flyway.enabled=true",
                        "--spring.jpa.hibernate.ddl-auto=validate",
                        "--spring.session.jdbc.initialize-schema=never",
                        "--usi.bootstrap-admin.enabled=false");
        jdbc = context.getBean(JdbcTemplate.class);
        repository = context.getBean(CaseReadStateRepository.class);
    }

    @AfterAll
    static void stopApplication() {
        if (context != null) context.close();
        POSTGRES.stop();
    }

    @BeforeEach
    void clearReadStates() {
        jdbc.update("DELETE FROM case_read_states");
        jdbc.update("DELETE FROM messages");
        jdbc.update("DELETE FROM cases");
    }

    @Test
    void twoUsersKeepIndependentReadPositionsForTheSameCase() {
        Fixture fixture = fixture("shared-case");
        UUID firstMessage = customerMessage(fixture.caseId(), "provider-first", "first");
        UUID secondMessage = customerMessage(fixture.caseId(), "provider-second", "second");
        UUID firstUser = createUser();
        UUID secondUser = createUser();

        CaseReadStateEntity firstState = repository.saveAndFlush(new CaseReadStateEntity(
                firstUser, fixture.caseId(), firstMessage, Instant.parse("2026-09-23T10:00:00Z")));
        CaseReadStateEntity secondState = repository.saveAndFlush(new CaseReadStateEntity(
                secondUser, fixture.caseId(), secondMessage, Instant.parse("2026-09-23T10:05:00Z")));

        assertThat(firstState.lastReadMessageId()).isEqualTo(firstMessage);
        assertThat(secondState.lastReadMessageId()).isEqualTo(secondMessage);
        assertThat(repository.findByIdCaseId(fixture.caseId()))
                .extracting(CaseReadStateEntity::userId)
                .containsExactlyInAnyOrder(firstUser, secondUser);
        assertThat(repository.findByIdUserId(firstUser))
                .singleElement()
                .extracting(CaseReadStateEntity::lastReadMessageId)
                .isEqualTo(firstMessage);
        assertThat(repository.findByIdUserId(secondUser))
                .singleElement()
                .extracting(CaseReadStateEntity::lastReadMessageId)
                .isEqualTo(secondMessage);
    }

    @Test
    void lastReadMessageMustBelongToTheSameCase() {
        Fixture firstCase = fixture("case-a");
        Fixture secondCase = fixture("case-b");
        UUID foreignMessage = customerMessage(secondCase.caseId(), "provider-foreign", "foreign");
        UUID userId = createUser();

        assertThatThrownBy(() -> repository.saveAndFlush(new CaseReadStateEntity(
                        userId,
                        firstCase.caseId(),
                        foreignMessage,
                        Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM case_read_states", Integer.class)).isZero();
    }

    @Test
    void readPositionRequiresMessageAndTimestampAsAPair() {
        Fixture fixture = fixture("position-pair");
        UUID userId = createUser();
        UUID messageId = customerMessage(fixture.caseId(), "provider-pair", "pair");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO case_read_states (user_id, case_id, last_read_message_id, last_read_at)
                VALUES (?, ?, ?, NULL)
                """, userId, fixture.caseId(), messageId))
                .isInstanceOf(DataIntegrityViolationException.class);

        CaseReadStateEntity emptyPosition = repository.saveAndFlush(new CaseReadStateEntity(
                userId,
                fixture.caseId(),
                null,
                null));
        assertThat(emptyPosition.lastReadMessageId()).isNull();
        assertThat(emptyPosition.lastReadAt()).isNull();
        assertThat(emptyPosition.updatedAt()).isNotNull();
    }

    @Test
    void deletingOneUserCleansOnlyThatUsersPersonalProjection() {
        Fixture fixture = fixture("cleanup");
        UUID messageId = customerMessage(fixture.caseId(), "provider-cleanup", "cleanup");
        UUID removedUser = createUser();
        UUID survivingUser = createUser();

        repository.saveAndFlush(new CaseReadStateEntity(removedUser, fixture.caseId(), messageId, Instant.now()));
        repository.saveAndFlush(new CaseReadStateEntity(survivingUser, fixture.caseId(), messageId, Instant.now()));

        jdbc.update("DELETE FROM users WHERE id = ?", removedUser);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM case_read_states WHERE user_id = ?",
                Integer.class,
                removedUser)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM case_read_states WHERE user_id = ?",
                Integer.class,
                survivingUser)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cases WHERE id = ?", Integer.class, fixture.caseId()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM messages WHERE id = ?", Integer.class, messageId))
                .isEqualTo(1);
    }

    @Test
    void requiredReadStateIndexesExist() {
        var indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'case_read_states'",
                String.class);

        assertThat(indexes).contains(
                "pk_case_read_states",
                "idx_case_read_states_case_user",
                "idx_case_read_states_user_updated");
    }

    private static Fixture fixture(String label) {
        String suffix = label + "-" + UUID.randomUUID();
        UUID customerId = jdbc.queryForObject(
                "INSERT INTO customers (name, external_ref) VALUES (?, ?) RETURNING id",
                UUID.class,
                "Customer " + suffix,
                "customer-" + suffix);
        UUID integrationId = jdbc.queryForObject("""
                INSERT INTO integrations (
                    provider, display_name, status, health, workspace_external_id
                ) VALUES ('SLACK', ?, 'ENABLED', 'HEALTHY', ?)
                RETURNING id
                """,
                UUID.class,
                "Slack " + suffix,
                "workspace-" + suffix);
        UUID channelId = jdbc.queryForObject("""
                INSERT INTO channels (
                    integration_id, external_channel_id, name, customer_id,
                    ignored, grouping_strategy, active
                ) VALUES (?, ?, ?, ?, FALSE, 'SLACK_ROOT_THREAD', TRUE)
                RETURNING id
                """,
                UUID.class,
                integrationId,
                "channel-" + suffix,
                "support-" + suffix,
                customerId);
        UUID caseId = jdbc.queryForObject("""
                INSERT INTO cases (
                    customer_id, integration_id, channel_id, provider,
                    external_conversation_id, external_thread_key, status
                ) VALUES (?, ?, ?, 'SLACK', ?, ?, 'NEW')
                RETURNING id
                """,
                UUID.class,
                customerId,
                integrationId,
                channelId,
                "conversation-" + suffix,
                "thread-" + suffix);
        return new Fixture(caseId);
    }

    private static UUID customerMessage(UUID caseId, String externalMessageId, String body) {
        return jdbc.queryForObject("""
                INSERT INTO messages (
                    case_id, external_message_id, external_thread_key, kind,
                    author_external_id, body, body_format, inbound,
                    provider_created_at, correlation_id
                ) VALUES (?, ?, 'thread-read-state', 'CUSTOMER', 'U-customer', ?,
                          'PLAIN_TEXT', TRUE, CURRENT_TIMESTAMP, ?)
                RETURNING id
                """,
                UUID.class,
                caseId,
                externalMessageId,
                body,
                "corr-" + UUID.randomUUID());
    }

    private static UUID createUser() {
        String suffix = UUID.randomUUID().toString();
        return jdbc.queryForObject(
                "INSERT INTO users (email, display_name, role, active) VALUES (?, ?, 'USER', TRUE) RETURNING id",
                UUID.class,
                "read-state-" + suffix + "@example.test",
                "Read State " + suffix);
    }

    private record Fixture(UUID caseId) {
    }
}
