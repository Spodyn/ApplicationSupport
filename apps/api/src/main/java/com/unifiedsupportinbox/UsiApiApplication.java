package com.unifiedsupportinbox;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.modulith.Modulith;

@Modulith(systemName = "Unified Support Inbox")
@ConfigurationPropertiesScan
public class UsiApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(UsiApiApplication.class, args);
    }
}
