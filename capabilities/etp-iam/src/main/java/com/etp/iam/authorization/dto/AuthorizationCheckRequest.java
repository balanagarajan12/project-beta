package com.etp.iam.authorization.dto;

import jakarta.validation.constraints.NotBlank;

public record AuthorizationCheckRequest(
		@NotBlank(message = "application is required")
		String application,

		@NotBlank(message = "permission is required")
		String permission,

		String tenant
) {
}
