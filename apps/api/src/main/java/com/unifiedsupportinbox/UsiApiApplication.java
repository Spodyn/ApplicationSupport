package com.unifiedsupportinbox;

import org.springframework.boot.SpringApplication;
import org.springframework.modulith.Modulith;

@Modulith(systemName = "Unified Support Inbox")
public class UsiApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(UsiApiApplication.class, args);
    }
}
