package com.unifiedsupportinbox;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModulithArchitectureTests {

    @Test
    void verifiesModularMonolithStructure() {
        ApplicationModules.of(UsiApiApplication.class).verify();
    }
}
