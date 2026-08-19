package com.etp.iam.authentication;

import com.etp.iam.authentication.dto.LoginRequest;
import com.etp.iam.authentication.dto.LoginResponse;
import com.etp.iam.authentication.dto.TokenRequest;
import com.etp.iam.authentication.dto.TokenResponse;
import com.etp.iam.openapi.AuthApiErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Human user and system-to-system authentication APIs")
public class AuthController {

	private final AuthService authService;

	public AuthController(AuthService authService) {
		this.authService = authService;
	}

	@Operation(
			summary = "Human user authentication",
			description = """
					Authenticate a human ETP user with username and password (Phase 1).

					**Caller:** ETP Core UI.

					This is the human login API. For machine/service authentication use \
					`POST /api/v1/auth/token` with `client_credentials`.

					**Security:**
					- Transmit credentials only over HTTPS in deployed environments.
					- Password is never persisted by IAM or returned in the response.
					- Password must not appear in URLs or application logs.
					- Keycloak confidential client credentials remain server-side in IAM only.

					Phase 1 uses server-side Direct Access Grant — a development choice, not Phase 2+ SSO/OIDC PKCE.
					""")
	@ApiResponses({
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
					responseCode = "200",
					description = "Successful authentication",
					content = @Content(schema = @Schema(implementation = LoginResponse.class))),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
					responseCode = "400",
					description = "Invalid request (validation failure or unsupported grant type on token endpoint)",
					content = @Content(schema = @Schema(implementation = AuthApiErrorResponse.class))),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
					responseCode = "401",
					description = "Invalid username or password",
					content = @Content(schema = @Schema(implementation = AuthApiErrorResponse.class))),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
					responseCode = "502",
					description = "Keycloak or IAM token service unavailable",
					content = @Content(schema = @Schema(implementation = AuthApiErrorResponse.class)))
	})
	@PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public LoginResponse login(@Valid @RequestBody LoginRequest request) {
		return authService.login(request);
	}

	@Operation(
			summary = "System-to-system authentication",
			description = """
					Obtain an access token using OAuth2 `client_credentials` grant.

					**Caller:** backend or service applications.

					This is **not** the human user login API. For username/password human authentication use \
					`POST /api/v1/auth/login`.

					Only `grant_type=client_credentials` is supported. Other grant types return HTTP 400.

					**Security:**
					- Transmit `client_secret` only over HTTPS in deployed environments.
					- Client secret must not appear in URLs or application logs.
					""")
	@ApiResponses({
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
					responseCode = "200",
					description = "Token issued",
					content = @Content(schema = @Schema(implementation = TokenResponse.class))),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
					responseCode = "400",
					description = "Invalid request or unsupported grant_type",
					content = @Content(schema = @Schema(implementation = AuthApiErrorResponse.class))),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
					responseCode = "401",
					description = "Invalid client credentials",
					content = @Content(schema = @Schema(implementation = AuthApiErrorResponse.class))),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(
					responseCode = "502",
					description = "Keycloak token service unavailable",
					content = @Content(schema = @Schema(implementation = AuthApiErrorResponse.class)))
	})
	@PostMapping(value = "/token", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public TokenResponse requestToken(@Valid @RequestBody TokenRequest request) {
		return authService.requestToken(request);
	}
}
