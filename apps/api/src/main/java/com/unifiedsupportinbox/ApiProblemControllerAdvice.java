package com.unifiedsupportinbox;

import java.util.Comparator;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiProblemControllerAdvice {

    private final ApiProblemFactory problemFactory;

    public ApiProblemControllerAdvice(ApiProblemFactory problemFactory) {
        this.problemFactory = problemFactory;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiProblem> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        List<ApiProblem.FieldError> fieldErrors = exception.getBindingResult().getFieldErrors().stream()
                .map(this::toFieldError)
                .sorted(Comparator.comparing(ApiProblem.FieldError::field)
                        .thenComparing(ApiProblem.FieldError::message))
                .toList();

        ApiProblem problem = problemFactory.create(
                HttpStatus.BAD_REQUEST,
                ApiProblemCode.VALIDATION_FAILED,
                "Validation failed",
                "One or more request fields are invalid.",
                request,
                fieldErrors);
        return response(problem);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiProblem> handleUnreadableRequest(
            HttpMessageNotReadableException exception,
            HttpServletRequest request) {
        ApiProblem problem = problemFactory.create(
                HttpStatus.BAD_REQUEST,
                ApiProblemCode.VALIDATION_FAILED,
                "Validation failed",
                "The request body is invalid or malformed.",
                request,
                List.of());
        return response(problem);
    }

    @ExceptionHandler(InvalidCursorException.class)
    public ResponseEntity<ApiProblem> handleInvalidCursor(
            InvalidCursorException exception,
            HttpServletRequest request) {
        ApiProblem problem = problemFactory.create(
                HttpStatus.BAD_REQUEST,
                ApiProblemCode.INVALID_CURSOR,
                "Invalid cursor",
                exception.reason().publicDetail(),
                request,
                List.of());
        return response(problem);
    }

    @ExceptionHandler(ApiProblemException.class)
    public ResponseEntity<ApiProblem> handleControlledProblem(
            ApiProblemException exception,
            HttpServletRequest request) {
        return response(problemFactory.fromException(exception, request));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiProblem> handleUnexpectedFailure(
            Exception exception,
            HttpServletRequest request) {
        ApiProblem problem = problemFactory.create(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ApiProblemCode.INTERNAL_ERROR,
                "Internal server error",
                "An unexpected error occurred.",
                request,
                List.of());
        return response(problem);
    }

    private ApiProblem.FieldError toFieldError(FieldError error) {
        String message = error.getDefaultMessage();
        if (message == null || message.isBlank()) {
            message = "Invalid value";
        }
        return new ApiProblem.FieldError(error.getField(), message);
    }

    private ResponseEntity<ApiProblem> response(ApiProblem problem) {
        return ResponseEntity.status(problem.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
