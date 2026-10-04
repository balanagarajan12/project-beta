package com.etp.migration.migration.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiFieldError(
		String field,
		String rejectedValue,
		String message,
		List<String> allowedValues
) {
}
