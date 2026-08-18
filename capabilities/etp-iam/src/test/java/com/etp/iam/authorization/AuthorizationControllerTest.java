package com.etp.iam.authorization;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.etp.iam.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = AuthorizationController.class)
@Import({AuthorizationExceptionHandler.class, SecurityConfig.class})
class AuthorizationControllerTest {

	private static final String APPLICATION = "Identity & Access Management";
	private static final String PERMISSION = "Create IAM";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private AuthorizationService authorizationService;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	@Test
	void check_returnsAuthorizedTrueWhenAuthenticatedAndAuthorized() throws Exception {
		when(authorizationService.isAuthorized(any(), eq(APPLICATION), eq(PERMISSION), isNull())).thenReturn(true);

		mockMvc.perform(post("/api/v1/authorization/check")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "application": "Identity & Access Management",
								  "permission": "Create IAM"
								}
								"""))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.authorized").value(true));
	}

	@Test
	void check_returnsAuthorizedFalseWhenAuthenticatedButUnauthorizedPermission() throws Exception {
		when(authorizationService.isAuthorized(any(), eq(APPLICATION), eq("Delete IAM"), isNull())).thenReturn(false);

		mockMvc.perform(post("/api/v1/authorization/check")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "application": "Identity & Access Management",
								  "permission": "Delete IAM"
								}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.authorized").value(false));
	}

	@Test
	void check_returnsAuthorizedFalseWhenAuthenticatedButWrongApplication() throws Exception {
		when(authorizationService.isAuthorized(any(), eq("Other Application"), eq(PERMISSION), isNull())).thenReturn(false);

		mockMvc.perform(post("/api/v1/authorization/check")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "application": "Other Application",
								  "permission": "Create IAM"
								}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.authorized").value(false));
	}

	@Test
	void check_returnsBadRequestWhenApplicationMissing() throws Exception {
		mockMvc.perform(post("/api/v1/authorization/check")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "permission": "Create IAM"
								}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("invalid_request"))
				.andExpect(jsonPath("$.details.application").value("application is required"));
	}

	@Test
	void check_returnsBadRequestWhenPermissionMissing() throws Exception {
		mockMvc.perform(post("/api/v1/authorization/check")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "application": "Identity & Access Management"
								}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("invalid_request"))
				.andExpect(jsonPath("$.details.permission").value("permission is required"));
	}

	@Test
	void check_returnsBadRequestWhenApplicationBlank() throws Exception {
		mockMvc.perform(post("/api/v1/authorization/check")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "application": "",
								  "permission": "Create IAM"
								}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("invalid_request"))
				.andExpect(jsonPath("$.details.application").value("application is required"));
	}

	@Test
	void check_returnsBadRequestWhenPermissionBlank() throws Exception {
		mockMvc.perform(post("/api/v1/authorization/check")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "application": "Identity & Access Management",
								  "permission": ""
								}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("invalid_request"))
				.andExpect(jsonPath("$.details.permission").value("permission is required"));
	}

	@Test
	void check_returnsUnauthorizedWhenUnauthenticated() throws Exception {
		mockMvc.perform(post("/api/v1/authorization/check")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "application": "Identity & Access Management",
								  "permission": "Create IAM"
								}
								"""))
				.andExpect(status().isUnauthorized());
	}
}
