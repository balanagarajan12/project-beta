package com.etp.iam.authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.etp.iam.authentication.dto.LoginRequest;
import com.etp.iam.authentication.dto.TokenRequest;
import com.etp.iam.authentication.dto.TokenResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

	private static final String LOGIN_CLIENT_ID = "etp-core-ui-login";
	private static final String LOGIN_CLIENT_SECRET = "login-secret";
	private static final String TEST_ACCESS_TOKEN = "header."
			+ "eyJzdWIiOiJ1c2VyLTEiLCJwcmVmZXJlZF91c2VybmFtZSI6ImFsaWNlIiwiZW1haWwiOiJhbGljZUBldHAubG9jYWwiLCJuYW1lIjoiQWxpY2UiLCJyZWFsbV9hY2Nlc3MiOnsicm9sZXMiOlsicGxhdGZvcm0tYWRtaW4iXX19"
			+ ".signature";

	@Mock
	private KeycloakTokenClient keycloakTokenClient;

	private AuthService authService;

	@org.junit.jupiter.api.BeforeEach
	void setUp() {
		authService = new AuthService(keycloakTokenClient, LOGIN_CLIENT_ID, LOGIN_CLIENT_SECRET);
	}

	@Test
	void login_returnsMappedUserAndToken() {
		TokenResponse tokenResponse = new TokenResponse(TEST_ACCESS_TOKEN, 300L, null, "Bearer", "openid");
		when(keycloakTokenClient.requestPasswordToken(LOGIN_CLIENT_ID, LOGIN_CLIENT_SECRET, "alice", "secret"))
				.thenReturn(tokenResponse);

		var response = authService.login(new LoginRequest("alice", "secret"));

		assertThat(response.accessToken()).isEqualTo(TEST_ACCESS_TOKEN);
		assertThat(response.user().username()).isEqualTo("alice");
		assertThat(response.user().email()).isEqualTo("alice@etp.local");
		assertThat(response.user().displayName()).isEqualTo("Alice");
		assertThat(response.user().roles()).containsExactly("platform-admin");
		assertThat(response.expiresAt()).isNotBlank();
	}

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
