package com.unifiedsupportinbox;

import java.util.List;

/** RFC problem+json payload extended with a stable USI code and correlation ID. */
public record ApiProblem(
        String code,
        String title,
        int status,
        String detail,
        String correlationId,
        List<FieldError> fieldErrors) {

    public ApiProblem {
        fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    }

    public record FieldError(String field, String message) {
    }
}
