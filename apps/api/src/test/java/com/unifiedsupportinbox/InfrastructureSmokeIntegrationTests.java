package com.unifiedsupportinbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;

import com.unifiedsupportinbox.testing.TestInfrastructure;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class InfrastructureSmokeIntegrationTests {

    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();
    private static final RabbitMQContainer RABBITMQ = TestInfrastructure.rabbitMq();
    private static final GenericContainer<?> MINIO = TestInfrastructure.minio();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        POSTGRES.start();
        RABBITMQ.start();
        MINIO.start();

        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);
        registry.add("spring.rabbitmq.virtual-host", () -> "/");
        registry.add("management.health.rabbit.enabled", () -> true);
    }

    @AfterAll
    static void stopInfrastructure() {
        MINIO.stop();
        RABBITMQ.stop();
        POSTGRES.stop();
    }

    @Test
    void bootsOnRandomPortWithRealPostgresAndFlyway() throws Exception {
        HttpResponse<String> health = get("http://localhost:" + port + "/actuator/health");

        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("UP");
        assertThat(queryBoolean("SELECT to_regclass('public.flyway_schema_history') IS NOT NULL")).isTrue();
        assertThat(queryString("SELECT current_database()")).isEqualTo(POSTGRES.getDatabaseName());
    }

    @Test
    void reachesRabbitMqAndS3CompatibleStorage() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(RABBITMQ.getHost(), RABBITMQ.getMappedPort(5672)), 2_000);
            assertThat(socket.isConnected()).isTrue();
        }

        String minioBaseUrl = "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
        HttpResponse<String> ready = get(minioBaseUrl + "/minio/health/ready");
        assertThat(ready.statusCode()).isEqualTo(200);
    }

    @Test
    void resetsDatabaseToFreshFlywayState() throws Exception {
        execute("CREATE TABLE isolation_probe (id integer primary key)");
        execute("INSERT INTO isolation_probe (id) VALUES (1)");
        assertThat(queryBoolean("SELECT to_regclass('public.isolation_probe') IS NOT NULL")).isTrue();

        TestInfrastructure.resetPostgres(POSTGRES);

        assertThat(queryBoolean("SELECT to_regclass('public.isolation_probe') IS NULL")).isTrue();
        assertThat(queryBoolean("SELECT to_regclass('public.flyway_schema_history') IS NOT NULL")).isTrue();
        assertThat(queryBoolean("SELECT to_regclass('public.inbound_events') IS NOT NULL")).isTrue();
        assertThat(queryBoolean("SELECT to_regclass('public.outbox_events') IS NOT NULL")).isTrue();
    }

    private HttpResponse<String> get(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private boolean queryBoolean(String sql) throws SQLException {
        try (Connection connection = connection();
             var statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getBoolean(1);
        }
    }

    private String queryString(String sql) throws SQLException {
        try (Connection connection = connection();
             var statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
