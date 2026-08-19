package com.etp.iam.authentication;

import com.etp.iam.authentication.dto.LoginRequest;
import com.etp.iam.authentication.dto.LoginResponse;
import com.etp.iam.authentication.dto.TokenRequest;
import com.etp.iam.authentication.dto.TokenResponse;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

	private static final String CLIENT_CREDENTIALS_GRANT = "client_credentials";

	private final KeycloakTokenClient keycloakTokenClient;
	private final String loginClientId;
	private final String loginClientSecret;

	public AuthService(
			KeycloakTokenClient keycloakTokenClient,
			@Value("${keycloak.login-client-id}") String loginClientId,
			@Value("${keycloak.login-client-secret}") String loginClientSecret) {
		this.keycloakTokenClient = keycloakTokenClient;
		this.loginClientId = loginClientId;
		this.loginClientSecret = loginClientSecret;
	}

	public LoginResponse login(LoginRequest request) {
		String username = request.username().trim();
		TokenResponse tokenResponse = keycloakTokenClient.requestPasswordToken(
				loginClientId,
				loginClientSecret,
				username,
				request.password());

		if (tokenResponse.accessToken() == null || tokenResponse.accessToken().isBlank()) {
			throw new KeycloakTokenException("Token service returned an invalid response", 502);
		}

		long expiresInSeconds = tokenResponse.expiresIn() != null ? tokenResponse.expiresIn() : 0L;
		Instant expiresAt = Instant.now().plusSeconds(expiresInSeconds);
		var user = AccessTokenUserMapper.mapUser(tokenResponse.accessToken(), username);

		return new LoginResponse(user, tokenResponse.accessToken(), expiresAt.toString());
	}

	public TokenResponse requestToken(TokenRequest request) {
		validateGrantType(request.grantType());
		return keycloakTokenClient.requestToken(
				request.clientId(),
				request.clientSecret(),
				request.grantType());
	}

	private void validateGrantType(String grantType) {
		if (!CLIENT_CREDENTIALS_GRANT.equals(grantType)) {
			throw new InvalidGrantTypeException(
					"Only client_credentials grant_type is supported");
		}
	}
}
