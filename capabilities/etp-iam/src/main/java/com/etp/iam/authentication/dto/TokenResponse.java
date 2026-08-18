package com.etp.iam.authentication.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record TokenResponse(
		@JsonProperty("access_token")
		String accessToken,

		@JsonProperty("expires_in")
		Long expiresIn,

		@JsonProperty("refresh_expires_in")
		Long refreshExpiresIn,

		@JsonProperty("token_type")
		String tokenType,

		@JsonProperty("scope")
		String scope
) {
}
