package com.etp.iam.authentication.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Human user login credentials (Phase 1)")
public record LoginRequest(
		@NotBlank(message = "username is required")
		@Schema(
				description = "Username or email accepted by Keycloak",
				requiredMode = Schema.RequiredMode.REQUIRED,
				example = "platform-admin")
		String username,

		@NotBlank(message = "password is required")
		@Schema(
				description = "User password. Sensitive — never persisted or returned. Use HTTPS in deployed environments.",
				requiredMode = Schema.RequiredMode.REQUIRED,
				format = "password",
				writeOnly = true,
				example = "your-password")
		String password
) {
}
