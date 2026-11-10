package com.unifiedsupportinbox.messaging.internal;

/** Shared by the command guard and the read projection; the browser never classifies failures. */
final class MessageRetryPolicy {
    private MessageRetryPolicy() { }
    static boolean repairable(String category, String code) {
        return "TRANSIENT".equals(category) || "PERMANENT".equals(category) && "INTEGRATION_DISABLED".equals(code);
    }
}
