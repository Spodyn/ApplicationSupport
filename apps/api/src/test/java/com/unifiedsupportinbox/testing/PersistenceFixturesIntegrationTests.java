package com.unifiedsupportinbox.testing;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
class PersistenceFixturesIntegrationTests {

    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void startDatabase() throws Exception {
        POSTGRES.start();
        TestInfrastructure.resetPostgres(POSTGRES);
        DataSource dataSource = new SimpleDriverDataSource(
                (java.sql.Driver) Class.forName(POSTGRES.getDriverClassName()).getDeclaredConstructor().newInstance(),
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterAll
    static void stopDatabase() {
        POSTGRES.stop();
    }

    @Test
    void createsMinimalLinkedRecordsWithParallelSafeNames() {
        PersistenceFixtures first = new PersistenceFixtures(jdbc);
        PersistenceFixtures second = new PersistenceFixtures(jdbc);

        PersistenceFixtures.Channel firstChannel = first.channel();
        PersistenceFixtures.Channel secondChannel = second.channel();

        assertThat(first.user().email()).isNotEqualTo(second.user().email());
        assertThat(first.customer().externalRef()).isNotEqualTo(second.customer().externalRef());
        assertThat(first.integration().workspaceExternalId()).isNotEqualTo(second.integration().workspaceExternalId());
        assertThat(firstChannel.externalChannelId()).isNotEqualTo(secondChannel.externalChannelId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM channels", Integer.class)).isEqualTo(2);

        first.cleanup();
        second.cleanup();
    }

    @Test
    void cleanupRemovesOnlyTheFixturesRecordsAndCanRunAgain() {
        PersistenceFixtures fixtures = new PersistenceFixtures(jdbc);
        PersistenceFixtures.Channel channel = fixtures.channel();

        fixtures.cleanup();
        fixtures.cleanup();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM channels WHERE id = ?", Integer.class, channel.id()))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM integrations", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
    }
}
