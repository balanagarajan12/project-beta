package com.etp.platform.common.api;

import java.time.Instant;
import java.util.List;

public record ErrorResponse(
        String code,
        String message,
        Instant timestamp,
        List<ErrorDetail> details
) {

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, Instant.now(), List.of());
    }

    public static ErrorResponse of(String code, String message, List<ErrorDetail> details) {
        return new ErrorResponse(code, message, Instant.now(), details);
    }
}
