package com.securebank.common;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
    BAD_CREDENTIALS(HttpStatus.UNAUTHORIZED),
    INVALID_OTP(HttpStatus.UNAUTHORIZED),
    OTP_EXPIRED(HttpStatus.UNAUTHORIZED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    KYC_NOT_VERIFIED(HttpStatus.FORBIDDEN),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    DUPLICATE(HttpStatus.CONFLICT),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT),
    ACCOUNT_INACTIVE(HttpStatus.CONFLICT),
    ACCOUNT_RESTRICTED(HttpStatus.CONFLICT),
    CONCURRENCY_CONFLICT(HttpStatus.CONFLICT),
    INSUFFICIENT_FUNDS(HttpStatus.UNPROCESSABLE_ENTITY),
    LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY),
    BENEFICIARY_REQUIRED(HttpStatus.UNPROCESSABLE_ENTITY),
    ACCOUNT_LOCKED(HttpStatus.LOCKED),
    TOO_MANY_ATTEMPTS(HttpStatus.TOO_MANY_REQUESTS),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
