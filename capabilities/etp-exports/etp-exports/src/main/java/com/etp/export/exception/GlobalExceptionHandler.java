package com.etp.export.exception;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final int MAX_UPSTREAM_BODY = 2000;

    @ExceptionHandler(ExportException.class)
    public ProblemDetail handleExport(ExportException ex) {
        log.warn("Export failed: {}", ex.getMessage());
        return problem(ex.getStatus(), "Export failed", ex.getMessage());
    }

    @ExceptionHandler(ExternalApiException.class)
    public ProblemDetail handleExternal(ExternalApiException ex) {
        log.warn("External API error {}: {}", ex.getUpstreamStatus().value(), truncate(ex.getUpstreamBody()));
        ProblemDetail pd = problem(HttpStatus.BAD_GATEWAY, "External API error", ex.getMessage());
        pd.setProperty("upstreamStatus", ex.getUpstreamStatus().value());
        pd.setProperty("upstreamBody", truncate(ex.getUpstreamBody()));
        return pd;
    }

    @ExceptionHandler(ResourceAccessException.class)
    public ProblemDetail handleAccess(ResourceAccessException ex) {
        log.warn("External API not reachable: {}", ex.getMessage());
        return problem(HttpStatus.GATEWAY_TIMEOUT, "External API not reachable", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(fe -> errors.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "Invalid export request", "Validation failed");
        pd.setProperty("errors", errors);
        return pd;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadable(HttpMessageNotReadableException ex) {
        String msg = ex.getMostSpecificCause().getMessage();
        return problem(HttpStatus.BAD_REQUEST, "Invalid export request", msg);
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        return pd;
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= MAX_UPSTREAM_BODY ? s : s.substring(0, MAX_UPSTREAM_BODY) + "...";
    }
}
