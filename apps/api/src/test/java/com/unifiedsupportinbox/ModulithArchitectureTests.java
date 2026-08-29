package com.unifiedsupportinbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModulithArchitectureTests {

    private static final Set<String> EXPECTED_MODULES = Set.of(
            "identity",
            "administration",
            "customer",
            "integration",
            "channel",
            "inbox",
            "messaging",
            "workflow",
            "sla",
            "notification",
            "analytics",
            "audit",
            "storage",
            "provider");

    @Test
    void verifiesModularMonolithStructure() {
        var modules = ApplicationModules.of(UsiApiApplication.class);

        modules.verify();

        assertThat(modules.stream().map(module -> module.getIdentifier().toString()).toList())
                .containsExactlyInAnyOrderElementsOf(EXPECTED_MODULES);
    }
}
