package com.etp.iam.authentication.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "OAuth2 client credentials token request (system-to-system only)")
public record TokenRequest(
		@NotBlank(message = "client_id is required")
		@JsonProperty("client_id")
		@Schema(
				description = "Keycloak client identifier",
				requiredMode = Schema.RequiredMode.REQUIRED,
				example = "iam-service-client")
		String clientId,

		@NotBlank(message = "client_secret is required")
		@JsonProperty("client_secret")
		@Schema(
				description = "Keycloak client secret. Sensitive — use HTTPS in deployed environments; never log or expose in URLs.",
				requiredMode = Schema.RequiredMode.REQUIRED,
				format = "password",
				writeOnly = true,
				example = "your-client-secret")
		String clientSecret,

		@NotBlank(message = "grant_type is required")
		@JsonProperty("grant_type")
		@Schema(
				description = "OAuth2 grant type. Only `client_credentials` is supported.",
				requiredMode = Schema.RequiredMode.REQUIRED,
				example = "client_credentials",
				allowableValues = {"client_credentials"})
		String grantType
) {
}
