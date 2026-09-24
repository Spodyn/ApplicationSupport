package com.unifiedsupportinbox.integration;

/** Secret-safe result of an administrator-requested provider connectivity check. */
public record IntegrationConnectionTestView(
        IntegrationView integration,
        IntegrationConnectionTester.Outcome outcome,
        String errorCode) {
}
