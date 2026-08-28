package com.unifiedsupportinbox.testing;

import org.flywaydb.core.Flyway;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared builders for isolated backend integration-test infrastructure.
 *
 * <p>Each test suite owns the containers it creates from this class. There are
 * deliberately no process-wide singleton containers, so suites can execute in
 * parallel without sharing databases, queues, object storage, or host ports.</p>
 */
public final class TestInfrastructure {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18.6");
    private static final DockerImageName RABBITMQ_IMAGE = DockerImageName.parse("rabbitmq:4.3.5-management");
    private static final DockerImageName MINIO_IMAGE = DockerImageName.parse("minio/minio:RELEASE.2025-09-07T16-13-09Z");

    private TestInfrastructure() {
    }

    public static PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(POSTGRES_IMAGE)
                .withDatabaseName("usi_test")
                .withUsername("usi_test")
                .withPassword("usi_test");
    }

    public static RabbitMQContainer rabbitMq() {
        return new RabbitMQContainer(RABBITMQ_IMAGE);
    }

    /**
     * Creates optional S3-compatible storage for suites that exercise object
     * storage. The container is intentionally not shared globally.
     */
    public static GenericContainer<?> minio() {
        GenericContainer<?> minio = new GenericContainer<>(MINIO_IMAGE);
        minio.withEnv("MINIO_ROOT_USER", "usi-test-access");
        minio.withEnv("MINIO_ROOT_PASSWORD", "usi-test-secret-key");
        minio.withCommand("server", "/data", "--console-address", ":9001");
        minio.withExposedPorts(9000);
        minio.waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000).forStatusCode(200));
        return minio;
    }

    /**
     * Resets one suite-owned PostgreSQL instance to a fresh Flyway-managed
     * schema. Once E03 adds migrations this method automatically reapplies the
     * complete migration chain from zero.
     */
    public static void resetPostgres(PostgreSQLContainer postgres) {
        Flyway flyway = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
    }
}
