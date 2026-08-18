package com.etp.iam.authentication.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record TokenRequest(
		@NotBlank(message = "client_id is required")
		@JsonProperty("client_id")
		String clientId,

		@NotBlank(message = "client_secret is required")
		@JsonProperty("client_secret")
		String clientSecret,

		@NotBlank(message = "grant_type is required")
		@JsonProperty("grant_type")
		String grantType
) {
}
