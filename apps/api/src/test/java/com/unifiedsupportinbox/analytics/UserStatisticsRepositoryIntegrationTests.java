package com.unifiedsupportinbox.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.unifiedsupportinbox.UsiApiApplication;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** PostgreSQL history attribution and dimension-filter regression. */
@Tag("integration")
class UserStatisticsRepositoryIntegrationTests {
    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();
    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static UserStatisticsRepository repository;
    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

    @BeforeAll
    static void startApplication() {
        POSTGRES.start();
        context = new SpringApplicationBuilder(UsiApiApplication.class).profiles("test").run(
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
        repository = context.getBean(UserStatisticsRepository.class);
    }

    @AfterAll
    static void stopApplication() {
        if (context != null) context.close();
        POSTGRES.stop();
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM analytics_daily_case_metrics");
        jdbc.update("DELETE FROM users");
    }

    @Test
    void inactiveActorRetainsHistoricalAttributionAndWeightedDurations() {
        UUID inactive = user("former-agent@example.com", false);
        UUID other = user("current-agent@example.com", true);
        metric(inactive, DAY, "SLACK", 2, 2, 75, 1, 90);
        metric(inactive, DAY.plusDays(1), "SLACK", 1, 1, 30, 2, 240);
        metric(other, DAY, "SLACK", 8, 7, 700, 6, 600);

        UserStatisticsService.Metrics report = repository.find(inactive, DAY,
                DAY.plusDays(2), "UTC", null, null).orElseThrow();
        assertThat(report.userId()).isEqualTo(inactive);
        assertThat(report.active()).isFalse();
        assertThat(report.claimed()).isEqualTo(3);
        assertThat(report.firstResponses()).isEqualTo(3);
        assertThat(report.averageFirstResponseSeconds()).isEqualTo(35);
        assertThat(report.resolved()).isEqualTo(3);
        assertThat(report.averageResolutionSeconds()).isEqualTo(110);

        UserStatisticsService.Metrics absent = repository.find(inactive, DAY.plusDays(2),
                DAY.plusDays(2), "UTC", null, null).orElseThrow();
        assertThat(absent.claimed()).isZero();
        assertThat(absent.averageFirstResponseSeconds()).isNull();
        assertThat(absent.averageResolutionSeconds()).isNull();
    }

    @Test
    void providerAndDateFilteringCannotMixAnotherUsersCounters() {
        UUID actor = user("agent@example.com", true);
        UUID other = user("other@example.com", true);
        metric(actor, DAY, "SLACK", 1, 1, 20, 1, 60);
        metric(actor, DAY.plusDays(1), "TEAMS", 3, 2, 100, 2, 200);
        metric(other, DAY, "SLACK", 9, 9, 1000, 9, 1000);

        assertThat(repository.find(actor, DAY, DAY.plusDays(1), "UTC", "SLACK", null)
                .orElseThrow().claimed()).isEqualTo(1);
        assertThat(repository.find(actor, DAY.plusDays(1), DAY.plusDays(1), "UTC", null, null)
                .orElseThrow().claimed()).isEqualTo(3);
        assertThat(repository.find(actor, DAY, DAY.plusDays(1), "Europe/Warsaw", null, null)
                .orElseThrow().claimed()).isZero();
        assertThat(repository.find(UUID.randomUUID(), DAY, DAY.plusDays(1), "UTC", null, null))
                .isEmpty();
    }

    private static UUID user(String email, boolean active) {
        return jdbc.queryForObject("""
                INSERT INTO users (email, display_name, role, active)
                VALUES (?, ?, 'USER', ?) RETURNING id
                """, UUID.class, email, email, active);
    }

    private static void metric(UUID id, LocalDate day, String provider,
            long claims, long firstResponses, long firstResponseSeconds,
            long resolutions, long resolutionSeconds) {
        jdbc.update("""
                INSERT INTO analytics_daily_case_metrics (
                    reporting_date, timezone_id, user_id, provider, claimed_count,
                    first_response_count, first_response_duration_seconds,
                    resolved_count, resolution_duration_seconds)
                VALUES (?, 'UTC', ?, ?, ?, ?, ?, ?, ?)
                """, day, id, provider, claims, firstResponses, firstResponseSeconds,
                resolutions, resolutionSeconds);
    }
}
