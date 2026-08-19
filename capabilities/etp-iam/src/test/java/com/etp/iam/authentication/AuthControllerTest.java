package com.etp.iam.authentication;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.etp.iam.authentication.dto.LoginResponse;
import com.etp.iam.authentication.dto.TokenResponse;
import com.etp.iam.authentication.dto.UserResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(AuthExceptionHandler.class)
class AuthControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private AuthService authService;

	@Test
	void login_returnsLoginResponse() throws Exception {
		when(authService.login(any())).thenReturn(
				new LoginResponse(
						new UserResponse("user-1", "alice", "Alice", "alice@etp.local", List.of("platform-admin")),
						"jwt-token",
						"2026-08-18T12:00:00Z"));

		mockMvc.perform(post("/api/v1/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "username": "alice",
								  "password": "secret"
								}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").value("jwt-token"))
				.andExpect(jsonPath("$.user.username").value("alice"))
				.andExpect(jsonPath("$.expiresAt").value("2026-08-18T12:00:00Z"));
	}

	@Test
	void login_returnsBadRequestWhenPasswordMissing() throws Exception {
		mockMvc.perform(post("/api/v1/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "username": "alice"
								}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("invalid_request"))
				.andExpect(jsonPath("$.details.password").value("password is required"));
	}

	@Test
	void requestToken_returnsTokenResponse() throws Exception {
		when(authService.requestToken(any())).thenReturn(
				new TokenResponse("jwt-token", 300L, null, "Bearer", "openid"));

		mockMvc.perform(post("/api/v1/auth/token")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "client_id": "iam-client",
								  "client_secret": "secret",
								  "grant_type": "client_credentials"
								}
								"""))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.access_token").value("jwt-token"))
				.andExpect(jsonPath("$.expires_in").value(300))
				.andExpect(jsonPath("$.token_type").value("Bearer"))
				.andExpect(jsonPath("$.scope").value("openid"));
	}

	@Test
	void requestToken_returnsBadRequestWhenRequiredFieldsMissing() throws Exception {
		mockMvc.perform(post("/api/v1/auth/token")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "grant_type": "client_credentials"
								}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("invalid_request"))
				.andExpect(jsonPath("$.details.clientId").value("client_id is required"))
				.andExpect(jsonPath("$.details.clientSecret").value("client_secret is required"));
	}
}
