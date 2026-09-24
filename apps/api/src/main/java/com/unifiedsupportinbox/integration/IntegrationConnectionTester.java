package com.unifiedsupportinbox.integration;

import java.util.UUID;

/** Provider adapter boundary for an explicit, non-mutating integration connection test. */
public interface IntegrationConnectionTester {

    boolean supports(IntegrationProvider provider);

    Result test(Request request);

    record Request(
            UUID integrationId,
            IntegrationProvider provider,
            String workspaceExternalId,
            boolean secretConfigured) {
    }

    record Result(Outcome outcome, String errorCode) {
        public static Result success() { return new Result(Outcome.SUCCESS, null); }
        public static Result timeout() { return new Result(Outcome.TIMEOUT, "TEST_CONNECTION_TIMEOUT"); }
        public static Result unauthorized() { return new Result(Outcome.UNAUTHORIZED, "PROVIDER_UNAUTHORIZED"); }
        public static Result unavailable() { return new Result(Outcome.UNAVAILABLE, "PROVIDER_UNAVAILABLE"); }
    }

    enum Outcome {
        SUCCESS,
        TIMEOUT,
        UNAUTHORIZED,
        UNAVAILABLE
    }
}
