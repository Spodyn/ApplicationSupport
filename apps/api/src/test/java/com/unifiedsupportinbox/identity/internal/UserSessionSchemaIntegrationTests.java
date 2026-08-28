package com.unifiedsupportinbox.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unifiedsupportinbox.UsiApiApplication;
import com.unifiedsupportinbox.identity.UserRole;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import jakarta.persistence.EntityManagerFactory;
import java.time.Duration;
import java.time.Instant;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
class UserSessionSchemaIntegrationTests {

    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    private static ConfigurableApplicationContext context;
    private static UserAccountRepository users;
    private static UserSessionEligibilityPolicy sessionEligibility;
    private static JdbcIndexedSessionRepository sessions;
    private static JdbcTemplate jdbc;

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
                        "--spring.session.jdbc.initialize-schema=never");

        users = context.getBean(UserAccountRepository.class);
        sessionEligibility = context.getBean(UserSessionEligibilityPolicy.class);
        sessions = context.getBean(JdbcIndexedSessionRepository.class);
        jdbc = context.getBean(JdbcTemplate.class);
    }

    @AfterAll
    static void stopApplication() {
        if (context != null) {
            context.close();
        }
        POSTGRES.stop();
    }

    @BeforeEach
    void clearState() {
        jdbc.update("DELETE FROM spring_session_attributes");
        jdbc.update("DELETE FROM spring_session");
        jdbc.update("DELETE FROM users");
    }

    @Test
    void flywayOwnsCanonicalUserAndSpringSessionSchemaAndJpaValidatesIt() {
        Flyway flyway = context.getBean(Flyway.class);
        flyway.validate();

        assertThat(flyway.info().pending()).isEmpty();
        assertThat(queryInt(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success AND version = '3'"))
                .isEqualTo(1);
        assertThat(queryBoolean("SELECT to_regclass('public.users') IS NOT NULL")).isTrue();
        assertThat(queryBoolean("SELECT to_regclass('public.spring_session') IS NOT NULL")).isTrue();
        assertThat(queryBoolean(
                "SELECT to_regclass('public.spring_session_attributes') IS NOT NULL"))
                .isTrue();
        assertThat(context.getEnvironment().getProperty("spring.session.jdbc.initialize-schema"))
                .isEqualTo("never");
        assertThat(context.getEnvironment().getProperty("spring.session.timeout"))
                .isEqualTo("12h");
        assertThat(context.getEnvironment().getProperty("server.servlet.session.cookie.name"))
                .isEqualTo("USI_SESSION");

        EntityManagerFactory entityManagerFactory = context.getBean(EntityManagerFactory.class);
        assertThat(entityManagerFactory.getMetamodel().entity(UserAccount.class)).isNotNull();
    }

    @Test
    void userPersistenceNormalizesEmailUsesUuidV7AndRejectsCaseInsensitiveDuplicate() {
        UserAccount first = users.saveAndFlush(new UserAccount(
                "  Agent.One@Example.COM ",
                "Agent One",
                null,
                UserRole.USER,
                true,
                null,
                null));

        assertThat(first.id()).isNotNull();
        assertThat(first.id().version()).isEqualTo(7);
        assertThat(first.email()).isEqualTo("agent.one@example.com");
        assertThat(first.version()).isZero();

        assertThatThrownBy(() -> users.saveAndFlush(new UserAccount(
                "AGENT.ONE@example.com",
                "Duplicate",
                null,
                UserRole.USER,
                true,
                null,
                null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsUnknownRoleAndInvalidValidityWindow() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO users (email, display_name, role, active) VALUES (?, ?, ?, ?)",
                "owner@example.com",
                "Owner",
                "OWNER",
                true))
                .isInstanceOf(DataAccessException.class);

        Instant now = Instant.parse("2026-08-29T00:00:00Z");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO users "
                        + "(email, display_name, role, active, valid_from, valid_until) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                "invalid-window@example.com",
                "Invalid Window",
                "USER",
                true,
                now.plusSeconds(60),
                now))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void inactiveFutureAndExpiredUsersAreDeniedBySessionEligibilityPolicy() {
        Instant now = Instant.parse("2026-08-29T00:00:00Z");

        UserAccount eligible = users.saveAndFlush(new UserAccount(
                "eligible@example.com",
                "Eligible",
                null,
                UserRole.USER,
                true,
                now.minusSeconds(60),
                now.plusSeconds(60)));
        UserAccount inactive = users.saveAndFlush(new UserAccount(
                "inactive@example.com",
                "Inactive",
                null,
                UserRole.USER,
                false,
                null,
                null));
        UserAccount future = users.saveAndFlush(new UserAccount(
                "future@example.com",
                "Future",
                null,
                UserRole.USER,
                true,
                now.plusSeconds(1),
                now.plusSeconds(120)));
        UserAccount expired = users.saveAndFlush(new UserAccount(
                "expired@example.com",
                "Expired",
                null,
                UserRole.USER,
                true,
                now.minusSeconds(120),
                now));

        assertThat(sessionEligibility.isSessionAllowed(eligible.id(), now)).isTrue();
        assertThat(sessionEligibility.isSessionAllowed(inactive.id(), now)).isFalse();
        assertThat(sessionEligibility.isSessionAllowed(future.id(), now)).isFalse();
        assertThat(sessionEligibility.isSessionAllowed(expired.id(), now)).isFalse();
    }

    @Test
    void springSessionRepositoryPersistsServerSideSessionWithTwelveHourTimeout() {
        var session = sessions.createSession();
        session.setMaxInactiveInterval(Duration.ofHours(12));
        session.setAttribute("userId", "00000000-0000-7000-8000-000000000001");
        sessions.save(session);

        assertThat(queryInt("SELECT COUNT(*) FROM spring_session")).isEqualTo(1);
        assertThat(queryInt("SELECT MAX_INACTIVE_INTERVAL FROM spring_session")).isEqualTo(43_200);
        assertThat(queryInt("SELECT COUNT(*) FROM spring_session_attributes")).isEqualTo(1);

        var loaded = sessions.findById(session.getId());
        assertThat(loaded).isNotNull();
        assertThat((String) loaded.getAttribute("userId"))
                .isEqualTo("00000000-0000-7000-8000-000000000001");
    }

    private static int queryInt(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    private static boolean queryBoolean(String sql) {
        Boolean value = jdbc.queryForObject(sql, Boolean.class);
        return Boolean.TRUE.equals(value);
    }
}
