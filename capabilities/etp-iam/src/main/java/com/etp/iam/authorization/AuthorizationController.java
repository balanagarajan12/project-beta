package com.etp.iam.authorization;

import com.etp.iam.authorization.dto.AuthorizationCheckRequest;
import com.etp.iam.authorization.dto.AuthorizationCheckResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/authorization")
public class AuthorizationController {

	private final AuthorizationService authorizationService;

	public AuthorizationController(AuthorizationService authorizationService) {
		this.authorizationService = authorizationService;
	}

	@PostMapping(value = "/check", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public AuthorizationCheckResponse check(
			@AuthenticationPrincipal Jwt jwt,
			@Valid @RequestBody AuthorizationCheckRequest request) {
		boolean authorized = authorizationService.isAuthorized(
				jwt,
				request.application(),
				request.permission(),
				request.tenant());
		return new AuthorizationCheckResponse(authorized);
	}
}
