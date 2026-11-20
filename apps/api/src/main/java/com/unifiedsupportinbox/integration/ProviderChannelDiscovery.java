package com.unifiedsupportinbox.integration;

import java.time.Duration;
import java.util.UUID;

/** Provider-owned I/O and catalog reconciliation, invoked outside a database transaction. */
public interface ProviderChannelDiscovery {
    IntegrationProvider provider();
    int discover(ProviderConnectionConfiguration.Claim claim);
    final class Failure extends RuntimeException {
        public final String code;
        public final Duration retryAfter;
        public Failure(String code, Duration retryAfter) { super(code);this.code=code;this.retryAfter=retryAfter; }
    }
}
