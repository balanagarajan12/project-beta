package com.etp.iam.openapi;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/**
 * Documents the JSON error body returned by {@link com.etp.iam.authentication.AuthExceptionHandler}.
 */
@Schema(description = "Authentication API error response")
public class AuthApiErrorResponse {

	@Schema(
			description = "Error code",
			examples = {"invalid_request", "invalid_grant", "unsupported_grant_type", "token_service_unavailable"})
	public String error;

	@Schema(description = "Human-readable error message")
	public String message;

	@Schema(
			description = "Field-level validation errors (HTTP 400 only). Keys use Java bean property names.",
			nullable = true,
			example = "{\"password\":\"password is required\"}")
	public Map<String, String> details;
}
