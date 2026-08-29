package com.unifiedsupportinbox.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unifiedsupportinbox.ApiProblemException;
import com.unifiedsupportinbox.UsiApiApplication;
import com.unifiedsupportinbox.identity.UserRole;
import com.unifiedsupportinbox.testing.TestInfrastructure;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("integration")
@ExtendWith(OutputCaptureExtension.class)
class CredentialRehashIntegrationTests {

    private static final PostgreSQLContainer POSTGRES = TestInfrastructure.postgres();

    private static ConfigurableApplicationContext context;
    private static SessionAuthenticationService authenticationService;
    private static UserAccountRepository users;
    private static PasswordEncoder encoder;
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

        authenticationService = context.getBean(SessionAuthenticationService.class);
        users = context.getBean(UserAccountRepository.class);
        encoder = context.getBean(PasswordEncoder.class);
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
        jdbc.update("DELETE FROM spring_session_attributes");
        jdbc.update("DELETE FROM spring_session");
        jdbc.update(
                "UPDATE bootstrap_admin_state "
                        + "SET consumed = FALSE, consumed_at = NULL, admin_user_id = NULL "
                        + "WHERE id = 1");
        jdbc.update("DELETE FROM users");
    }

    @Test
    void successfulAuthenticationRehashesOlderCostParametersAtomicallyAndDoesNotLogSecrets(
            CapturedOutput output) {
        PasswordEncoder legacyEncoder = legacyEncoder();
        String raw = testValue();
        String oldHash = legacyEncoder.encode(raw);
        UserAccount user = users.saveAndFlush(new UserAccount(
                "rehash@example.com",
                "Rehash User",
                oldHash,
                UserRole.USER,
                true,
                null,
                null));

        assertThat(encoder.matches(raw, oldHash)).isTrue();
        assertThat(encoder.upgradeEncoding(oldHash)).isTrue();

        authenticationService.authenticate("rehash@example.com", raw);

        UserAccount refreshed = users.findById(user.id()).orElseThrow();
        String newHash = refreshed.passwordHash();
        assertThat(newHash).isNotEqualTo(oldHash).startsWith("$argon2id$");
        assertThat(encoder.matches(raw, newHash)).isTrue();
        assertThat(encoder.upgradeEncoding(newHash)).isFalse();
        assertThat(refreshed.lastLoginAt()).isNotNull();
        assertThat(output.getAll())
                .doesNotContain(raw)
                .doesNotContain(oldHash)
                .doesNotContain(newHash);
    }

    @Test
    void ineligibleAccountDoesNotRehashEvenWhenStoredCostParametersAreOld() {
        PasswordEncoder legacyEncoder = legacyEncoder();
        String raw = testValue();
        String oldHash = legacyEncoder.encode(raw);
        UserAccount user = users.saveAndFlush(new UserAccount(
                "inactive-rehash@example.com",
                "Inactive Rehash User",
                oldHash,
                UserRole.USER,
                false,
                null,
                null));

        assertThatThrownBy(() -> authenticationService.authenticate(
                "inactive-rehash@example.com",
                raw))
                .isInstanceOf(ApiProblemException.class);

        UserAccount refreshed = users.findById(user.id()).orElseThrow();
        assertThat(refreshed.passwordHash()).isEqualTo(oldHash);
        assertThat(refreshed.lastLoginAt()).isNull();
    }

    private static PasswordEncoder legacyEncoder() {
        return new Argon2CredentialEncoder(32_768, 2, 1, 32);
    }

    private static String testValue() {
        return String.join("-", "test", "only", "rehash", "candidate");
    }
}
