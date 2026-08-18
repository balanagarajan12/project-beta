package com.etp.iam.authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.etp.iam.authentication.dto.TokenRequest;
import com.etp.iam.authentication.dto.TokenResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

	@Mock
	private KeycloakTokenClient keycloakTokenClient;

	@InjectMocks
	private AuthService authService;

	@Test
	void requestToken_delegatesToKeycloakForClientCredentials() {
		TokenRequest request = new TokenRequest("iam-client", "secret", "client_credentials");
		TokenResponse expected = new TokenResponse("access-token", 300L, null, "Bearer", "openid");

		when(keycloakTokenClient.requestToken("iam-client", "secret", "client_credentials"))
				.thenReturn(expected);

		TokenResponse response = authService.requestToken(request);

		assertThat(response).isEqualTo(expected);
		verify(keycloakTokenClient).requestToken("iam-client", "secret", "client_credentials");
	}

	@Test
	void requestToken_rejectsUnsupportedGrantType() {
		TokenRequest request = new TokenRequest("iam-client", "secret", "password");

		assertThatThrownBy(() -> authService.requestToken(request))
				.isInstanceOf(InvalidGrantTypeException.class)
				.hasMessage("Only client_credentials grant_type is supported");
	}
}
