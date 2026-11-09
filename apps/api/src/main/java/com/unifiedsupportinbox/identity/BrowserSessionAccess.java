package com.unifiedsupportinbox.identity;

/** Server-side validity check used by long-lived transports; does not renew idle expiry. */
public interface BrowserSessionAccess {
    boolean isValid(String sessionId, String principalName);
}
