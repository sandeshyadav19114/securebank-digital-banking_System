package com.securebank.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Single place that converts every failure into the same {@link ApiError} JSON shape. */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> business(BusinessException ex, HttpServletRequest req) {
        log.info("business_error code={} message={}", ex.getCode(), ex.getMessage());
        return build(ex.getCode(), ex.getMessage(), req, null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> invalidBody(MethodArgumentNotValidException ex, HttpServletRequest req) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(f -> fields.putIfAbsent(f.getField(), f.getDefaultMessage()));
        return build(ErrorCode.VALIDATION_ERROR, "Request validation failed", req, fields);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> constraint(ConstraintViolationException ex, HttpServletRequest req) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getConstraintViolations().forEach(v -> fields.putIfAbsent(v.getPropertyPath().toString(), v.getMessage()));
        return build(ErrorCode.VALIDATION_ERROR, "Request validation failed", req, fields);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> unreadable(Exception ex, HttpServletRequest req) {
        return build(ErrorCode.VALIDATION_ERROR, "Malformed request", req, null);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiError> missingHeader(MissingRequestHeaderException ex, HttpServletRequest req) {
        return build(ErrorCode.VALIDATION_ERROR, "Missing required header: " + ex.getHeaderName(), req, null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> denied(AccessDeniedException ex, HttpServletRequest req) {
        return build(ErrorCode.FORBIDDEN, "Access denied", req, null);
    }

    @ExceptionHandler(ConcurrencyFailureException.class)
    public ResponseEntity<ApiError> concurrency(ConcurrencyFailureException ex, HttpServletRequest req) {
        log.warn("concurrency_failure {}", ex.getMessage());
        return build(ErrorCode.CONCURRENCY_CONFLICT, "The resource is busy, please retry", req, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception ex, HttpServletRequest req) {
        log.error("unexpected_error path={}", req.getRequestURI(), ex);
        return build(ErrorCode.INTERNAL_ERROR, "Unexpected error", req, null);
    }

    private ResponseEntity<ApiError> build(ErrorCode code, String message, HttpServletRequest req,
                                           Map<String, String> fields) {
        ApiError body = new ApiError(Instant.now(), code.status().value(), code.name(), message,
                req.getRequestURI(), MDC.get("requestId"), fields);
        return ResponseEntity.status(code.status()).body(body);
    }
}
