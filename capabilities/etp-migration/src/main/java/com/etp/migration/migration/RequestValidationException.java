package com.etp.migration.migration;

import com.etp.migration.migration.dto.ApiFieldError;
import java.util.List;

public class RequestValidationException extends RuntimeException {

	private final List<ApiFieldError> errors;

	public RequestValidationException(List<ApiFieldError> errors) {
		super("Request validation failed");
		this.errors = List.copyOf(errors);
	}

	public List<ApiFieldError> errors() {
		return errors;
	}
}
