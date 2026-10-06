package com.securebank.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(Instant timestamp, int status, String code, String message,
                       String path, String requestId, Map<String, String> fieldErrors) {

    public static ApiError of(ErrorCode code, String message, String path, String requestId) {
        return new ApiError(Instant.now(), code.status().value(), code.name(), message, path, requestId, null);
    }
}
