package com.unifiedsupportinbox.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.unifiedsupportinbox.testing.TestInfrastructure;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Tag("integration")
class AuthenticationBudgetMigrationIntegrationTests {
    @Test
    void upgradesImmediatelyPrecedingSnapshotWithoutChangingExistingAccounts() {
        try (var postgres = TestInfrastructure.postgres()) {
            postgres.start();
            var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            Flyway.configure().dataSource(source).target("40").load().migrate();
            var jdbc = new JdbcTemplate(source);
            jdbc.update("INSERT INTO users (email, display_name, role, active) VALUES (?, ?, 'USER', TRUE)",
                    "migration@example.com", "Migration preservation probe");
            String id = jdbc.queryForObject("SELECT id::text FROM users WHERE email = ?", String.class, "migration@example.com");
            var current = Flyway.configure().dataSource(source).target("41").load();
            assertThat(current.migrate().migrationsExecuted).isEqualTo(1);
            current.validate();
            assertThat(current.info().pending()).isEmpty();
            assertThat(jdbc.queryForObject("SELECT id::text FROM users WHERE email = ?", String.class, "migration@example.com")).isEqualTo(id);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM authentication_abuse_budgets", Integer.class)).isZero();
        }
    }
}
