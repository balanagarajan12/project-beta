package com.etp.iam.authentication.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "OAuth2 token response (Keycloak token endpoint shape)")
public record TokenResponse(
		@JsonProperty("access_token")
		@Schema(description = "JWT access token", example = "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...")
		String accessToken,

		@JsonProperty("expires_in")
		@Schema(description = "Access token lifetime in seconds", example = "300")
		Long expiresIn,

		@JsonProperty("refresh_expires_in")
		@Schema(description = "Refresh token lifetime in seconds when returned by Keycloak", nullable = true)
		Long refreshExpiresIn,

		@JsonProperty("token_type")
		@Schema(description = "Token type", example = "Bearer")
		String tokenType,

		@JsonProperty("scope")
		@Schema(description = "Granted scope", example = "openid")
		String scope
) {
}
