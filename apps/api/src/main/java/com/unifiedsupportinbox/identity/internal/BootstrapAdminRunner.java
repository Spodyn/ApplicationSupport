package com.unifiedsupportinbox.identity.internal;

import java.util.Arrays;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "usi.bootstrap-admin",
        name = "enabled",
        havingValue = "true")
class BootstrapAdminRunner implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(BootstrapAdminRunner.class);

    private final BootstrapAdminProperties properties;
    private final BootstrapAdminSecretReader secretReader;
    private final BootstrapAdminService bootstrapAdminService;

    BootstrapAdminRunner(
            BootstrapAdminProperties properties,
            BootstrapAdminSecretReader secretReader,
            BootstrapAdminService bootstrapAdminService) {
        this.properties = properties;
        this.secretReader = secretReader;
        this.bootstrapAdminService = bootstrapAdminService;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        BootstrapAdminProperties.RequiredConfiguration configuration =
                properties.requiredConfiguration();
        char[] password = secretReader.readPassword(configuration.passwordFile());
        try {
            UUID adminUserId = bootstrapAdminService.bootstrap(
                    configuration.email(),
                    configuration.displayName(),
                    password);
            LOGGER.info(
                    "Bootstrap administrator created successfully (userId={}). Remove the one-time bootstrap trigger before the next restart.",
                    adminUserId);
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}
