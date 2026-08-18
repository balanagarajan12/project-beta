package com.etp.iam.authentication;

import com.etp.iam.authentication.dto.TokenRequest;
import com.etp.iam.authentication.dto.TokenResponse;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

	private static final String CLIENT_CREDENTIALS_GRANT = "client_credentials";

	private final KeycloakTokenClient keycloakTokenClient;

	public AuthService(KeycloakTokenClient keycloakTokenClient) {
		this.keycloakTokenClient = keycloakTokenClient;
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
