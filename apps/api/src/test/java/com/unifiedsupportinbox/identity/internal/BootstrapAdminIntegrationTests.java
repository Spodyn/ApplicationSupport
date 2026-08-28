package com.unifiedsupportinbox.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unifiedsupportinbox.UsiApiApplication;
import com.unifiedsupportinbox.identity.UserRole;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
class BootstrapAdminIntegrationTests {

    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    private static ConfigurableApplicationContext context;
    private static BootstrapAdminService bootstrapAdminService;
    private static UserAccountRepository users;
    private static PasswordEncoder passwordEncoder;
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
                        "--spring.session.jdbc.initialize-schema=never",
                        "--usi.bootstrap-admin.enabled=false");

        bootstrapAdminService = context.getBean(BootstrapAdminService.class);
        users = context.getBean(UserAccountRepository.class);
        passwordEncoder = context.getBean("bootstrapAdminPasswordEncoder", PasswordEncoder.class);
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
    void resetState() {
        jdbc.update(
                "UPDATE bootstrap_admin_state "
                        + "SET consumed = FALSE, consumed_at = NULL, admin_user_id = NULL "
                        + "WHERE id = 1");
        jdbc.update("DELETE FROM users");
    }

    @Test
    void firstBootstrapCreatesActiveAdminWithArgon2IdHashAndConsumesDurableState() {
        char[] password = testPassword();
        try {
            UUID adminUserId = bootstrapAdminService.bootstrap(
                    "  Bootstrap.Admin@Example.COM ",
                    "Bootstrap Administrator",
                    password);

            UserAccount administrator = users.findById(adminUserId).orElseThrow();
            assertThat(administrator.email()).isEqualTo("bootstrap.admin@example.com");
            assertThat(administrator.role()).isEqualTo(UserRole.ADMIN);
            assertThat(administrator.active()).isTrue();
            assertThat(administrator.passwordHash()).startsWith("$argon2id$");
            assertThat(passwordEncoder.matches(
                    CharBuffer.wrap(password),
                    administrator.passwordHash()))
                    .isTrue();
            assertThat(queryBoolean(
                    "SELECT consumed FROM bootstrap_admin_state WHERE id = 1"))
                    .isTrue();
            assertThat(queryUuid(
                    "SELECT admin_user_id FROM bootstrap_admin_state WHERE id = 1"))
                    .isEqualTo(adminUserId);
            assertThat(queryInt(
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE success AND version = '4'"))
                    .isEqualTo(1);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    @Test
    void secondBootstrapRemainsBlockedEvenAfterOriginalAdminIsDeactivated() {
        char[] firstPassword = testPassword();
        UUID firstAdminId;
        try {
            firstAdminId = bootstrapAdminService.bootstrap(
                    "first.admin@example.com",
                    "First Administrator",
                    firstPassword);
        } finally {
            Arrays.fill(firstPassword, '\0');
        }

        UserAccount firstAdministrator = users.findById(firstAdminId).orElseThrow();
        firstAdministrator.setActive(false);
        users.saveAndFlush(firstAdministrator);

        char[] secondPassword = testPassword();
        try {
            assertThatThrownBy(() -> bootstrapAdminService.bootstrap(
                    "second.admin@example.com",
                    "Second Administrator",
                    secondPassword))
                    .isInstanceOf(BootstrapAdminException.class)
                    .hasMessageContaining("already been consumed");
        } finally {
            Arrays.fill(secondPassword, '\0');
        }

        assertThat(users.count()).isEqualTo(1);
        assertThat(queryBoolean("SELECT consumed FROM bootstrap_admin_state WHERE id = 1"))
                .isTrue();
    }

    @Test
    void bootstrapRefusesToRunWhenAnActiveAdminAlreadyExists() {
        users.saveAndFlush(new UserAccount(
                "existing.admin@example.com",
                "Existing Administrator",
                null,
                UserRole.ADMIN,
                true,
                null,
                null));

        char[] password = testPassword();
        try {
            assertThatThrownBy(() -> bootstrapAdminService.bootstrap(
                    "bootstrap.admin@example.com",
                    "Bootstrap Administrator",
                    password))
                    .isInstanceOf(BootstrapAdminException.class)
                    .hasMessageContaining("active administrator already exists");
        } finally {
            Arrays.fill(password, '\0');
        }

        assertThat(users.count()).isEqualTo(1);
        assertThat(queryBoolean("SELECT consumed FROM bootstrap_admin_state WHERE id = 1"))
                .isFalse();
    }

    @Test
    void concurrentBootstrapAttemptsProduceExactlyOneAdministrator() throws Exception {
        int contenders = 6;
        ExecutorService executor = Executors.newFixedThreadPool(contenders);
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();

        try {
            for (int index = 0; index < contenders; index++) {
                int contender = index;
                results.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        return false;
                    }
                    char[] password = testPassword();
                    try {
                        bootstrapAdminService.bootstrap(
                                "bootstrap-" + contender + "@example.com",
                                "Bootstrap Administrator " + contender,
                                password);
                        return true;
                    } catch (BootstrapAdminException expected) {
                        return false;
                    } finally {
                        Arrays.fill(password, '\0');
                    }
                }));
            }

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            long successes = 0;
            for (Future<Boolean> result : results) {
                if (Boolean.TRUE.equals(result.get(20, TimeUnit.SECONDS))) {
                    successes++;
                }
            }

            assertThat(successes).isEqualTo(1);
            assertThat(users.count()).isEqualTo(1);
            assertThat(queryBoolean("SELECT consumed FROM bootstrap_admin_state WHERE id = 1"))
                    .isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    private static char[] testPassword() {
        return String.join("-", "test", "only", "bootstrap", "credential").toCharArray();
    }

    private static int queryInt(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    private static boolean queryBoolean(String sql) {
        Boolean value = jdbc.queryForObject(sql, Boolean.class);
        return Boolean.TRUE.equals(value);
    }

    private static UUID queryUuid(String sql) {
        return jdbc.queryForObject(sql, UUID.class);
    }
}
