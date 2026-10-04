package com.etp.migration.migration.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(
		Instant timestamp,
		int status,
		String error,
		String message,
		String path,
		String code,
		List<ApiFieldError> errors
) {
	public ApiErrorResponse(Instant timestamp, int status, String error, String message, String path) {
		this(timestamp, status, error, message, path, null, null);
	}

	public ApiErrorResponse(Instant timestamp, int status, String error, String message, String path, String code) {
		this(timestamp, status, error, message, path, code, null);
	}
}
