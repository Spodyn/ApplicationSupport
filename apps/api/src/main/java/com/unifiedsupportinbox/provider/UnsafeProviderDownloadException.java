package com.unifiedsupportinbox.provider;

/** Raised when a provider attachment download violates outbound-network policy. */
public final class UnsafeProviderDownloadException extends RuntimeException {
    public UnsafeProviderDownloadException(String message) { super(message); }
    public UnsafeProviderDownloadException(String message, Throwable cause) { super(message, cause); }
}
