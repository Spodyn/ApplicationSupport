package com.unifiedsupportinbox.messaging.internal;

/** Shared by the command guard and the read projection; the browser never classifies failures. */
final class MessageRetryPolicy {
    private MessageRetryPolicy() { }
    static boolean repairable(String category, String code) {
        return "TRANSIENT".equals(category) || "PERMANENT".equals(category) && java.util.Set.of(
                "INTEGRATION_DISABLED", "TEAMS_FILE_PERMISSION_REQUIRED", "TEAMS_FILE_STORAGE_CONFIGURATION_REQUIRED",
                "TEAMS_AUTH_FAILED", "TEAMS_PERMISSION_DENIED", "TEAMS_CLIENT_SECRET_MISSING", "TEAMS_FILE_CONTEXT_INACTIVE")
                .contains(java.util.Objects.toString(code,""));
    }
}
