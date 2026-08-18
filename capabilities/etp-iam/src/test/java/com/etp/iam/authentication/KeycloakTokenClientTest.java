package com.etp.iam.authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

import com.etp.iam.authentication.dto.TokenResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class KeycloakTokenClientTest {

	private static final String TOKEN_URL = "http://localhost:8180/realms/etp/protocol/openid-connect/token";

	private MockRestServiceServer mockServer;
	private KeycloakTokenClient keycloakTokenClient;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		mockServer = MockRestServiceServer.bindTo(builder).build();
		RestClient restClient = builder.build();
		keycloakTokenClient = new KeycloakTokenClient(restClient, TOKEN_URL);
	}

	@Test
	void requestToken_returnsTokenResponseWhenKeycloakSucceeds() {
		mockServer.expect(requestTo(TOKEN_URL))
				.andExpect(method(HttpMethod.POST))
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
				.andExpect(content().string(
						"grant_type=client_credentials&client_id=iam-client&client_secret=secret"))
				.andRespond(withSuccess("""
						{
						  "access_token": "jwt-token",
						  "expires_in": 300,
						  "token_type": "Bearer",
						  "scope": "profile"
						}
						""", MediaType.APPLICATION_JSON));

		TokenResponse response = keycloakTokenClient.requestToken("iam-client", "secret", "client_credentials");

		assertThat(response.accessToken()).isEqualTo("jwt-token");
		assertThat(response.expiresIn()).isEqualTo(300L);
		assertThat(response.tokenType()).isEqualTo("Bearer");
		assertThat(response.scope()).isEqualTo("profile");
		mockServer.verify();
	}

	@Test
	void requestToken_mapsUnauthorizedResponseToAuthenticationException() {
		mockServer.expect(requestTo(TOKEN_URL))
				.andExpect(method(HttpMethod.POST))
				.andRespond(withUnauthorizedRequest().body("""
						{
						  "error": "invalid_client",
						  "error_description": "Invalid client credentials"
						}
						"""));

		assertThatThrownBy(() -> keycloakTokenClient.requestToken("iam-client", "bad-secret", "client_credentials"))
				.isInstanceOf(KeycloakAuthenticationException.class)
				.hasMessage("Invalid client credentials")
				.extracting("error")
				.isEqualTo("invalid_client");

		mockServer.verify();
	}

	@Test
	void requestToken_mapsBadRequestResponseToAuthenticationException() {
		mockServer.expect(requestTo(TOKEN_URL))
				.andExpect(method(HttpMethod.POST))
				.andRespond(withBadRequest().body("""
						{
						  "error": "unauthorized_client",
						  "error_description": "Client not allowed for this grant type"
						}
						"""));

		assertThatThrownBy(() -> keycloakTokenClient.requestToken("iam-client", "secret", "client_credentials"))
				.isInstanceOf(KeycloakAuthenticationException.class)
				.hasMessage("Client not allowed for this grant type");

		mockServer.verify();
	}
}
