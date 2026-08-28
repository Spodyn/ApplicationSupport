package com.unifiedsupportinbox;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class ApiProblemFactory {

    static final String CORRELATION_ID_ATTRIBUTE = "usi.correlationId";
    private static final Pattern SAFE_CORRELATION_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    public ApiProblem fromException(ApiProblemException exception, HttpServletRequest request) {
        return create(
                exception.status(),
                exception.code(),
                exception.title(),
                exception.getMessage(),
                request,
                List.of());
    }

    public ApiProblem create(
            HttpStatus status,
            ApiProblemCode code,
            String title,
            String detail,
            HttpServletRequest request,
            List<ApiProblem.FieldError> fieldErrors) {
        return new ApiProblem(
                code.name(),
                title,
                status.value(),
                detail,
                correlationId(request),
                fieldErrors);
    }

    private String correlationId(HttpServletRequest request) {
        Object existing = request.getAttribute(CORRELATION_ID_ATTRIBUTE);
        if (existing instanceof String value && SAFE_CORRELATION_ID.matcher(value).matches()) {
            return value;
        }

        String generated = UUID.randomUUID().toString();
        request.setAttribute(CORRELATION_ID_ATTRIBUTE, generated);
        return generated;
    }
}
