package com.etp.iam.authentication.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Successful human user authentication response")
public record LoginResponse(
		@Schema(description = "Authenticated user profile derived from the Keycloak access token")
		UserResponse user,

		@JsonProperty("accessToken")
		@Schema(
				description = "Keycloak JWT access token",
				example = "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...")
		String accessToken,

		@JsonProperty("expiresAt")
		@Schema(
				description = "Access token expiry instant (ISO-8601 UTC)",
				example = "2026-08-18T12:05:00Z")
		String expiresAt
) {
}
