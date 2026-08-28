package com.unifiedsupportinbox;

/** Stable machine-readable error codes exposed by the REST API. */
public enum ApiProblemCode {
    VALIDATION_FAILED,
    AUTHENTICATION_REQUIRED,
    ACCESS_DENIED,
    RESOURCE_NOT_FOUND,
    CONFLICT,
    RATE_LIMITED,
    PROVIDER_FAILURE,
    APPLICATION_FAILURE,
    INTERNAL_ERROR
}
