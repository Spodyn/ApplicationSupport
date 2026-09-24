package com.unifiedsupportinbox.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.unifiedsupportinbox.testing.TestInfrastructure;

@Tag("integration")
class AuditEventSchemaIntegrationTests {

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
    void auditEventsAreAppendOnly() {
        UUID id = insertAuditEvent();

        assertThatThrownBy(() -> jdbc.update("UPDATE audit_events SET action = 'CHANGED' WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_events WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void providesCaseEntityAndTimeIndexes() {
        List<String> indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'audit_events'",
                String.class);

        assertThat(indexes).contains(
                "idx_audit_events_case_occurred",
                "idx_audit_events_entity_occurred",
                "idx_audit_events_occurred");
    }

    private static UUID insertAuditEvent() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO audit_events (
                    id, actor_type, action, entity_type, entity_id, correlation_id, metadata_json
                ) VALUES (?, 'SYSTEM', 'CASE_CREATED', 'CASE', ?, 'correlation-audit-test', '{}'::jsonb)
                """, id, UUID.randomUUID());
        return id;
    }
}
