package com.etp.migration.migration.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(
		Instant timestamp,
		int status,
		String error,
		String message,
		String path,
		String code
) {
	public ApiErrorResponse(Instant timestamp, int status, String error, String message, String path) {
		this(timestamp, status, error, message, path, null);
	}
}
