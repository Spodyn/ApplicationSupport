package com.unifiedsupportinbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class UsiConfigurationPropertiesTests {

    @Autowired
    private UsiConfigurationProperties properties;

    @Autowired
    private DataSource dataSource;

    @Test
    void testProfileBindsTypedValidatedConfiguration() {
        assertThat(properties.deployment().profile())
                .isEqualTo(UsiConfigurationProperties.DeploymentProfile.TEST);
        assertThat(properties.publicBaseUrl().toString()).isEqualTo("http://localhost:3000");
        assertThat(properties.providerCallbacks().slack().getScheme()).isEqualTo("https");
        assertThat(properties.integrationSecrets().backend())
                .isEqualTo(UsiConfigurationProperties.SecretBackend.IN_MEMORY);
        assertThat(properties.security().cors().allowedOrigins()).isEmpty();
    }

    @Test
    void testProfileUsesDeterministicSmallDatasourcePool() {
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        HikariDataSource hikari = (HikariDataSource) dataSource;
        assertThat(hikari.getMaximumPoolSize()).isEqualTo(4);
        assertThat(hikari.getMinimumIdle()).isZero();
    }
}
