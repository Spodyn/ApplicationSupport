package com.unifiedsupportinbox.identity.internal;

final class BootstrapAdminException extends IllegalStateException {

    BootstrapAdminException(String message) {
        super(message);
    }

    BootstrapAdminException(String message, Throwable cause) {
        super(message, cause);
    }
}
