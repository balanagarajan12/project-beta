package com.etp.migration.openapi;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Loads the production config files (the test resources shadow them otherwise) through config-data, so
 * {@code application-dev.properties} keeps its normal precedence over {@code application.properties}.
 */
@SpringBootTest(properties = "spring.config.location=file:src/main/resources/")
@AutoConfigureMockMvc
class OpenApiExposureIntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	JwtDecoder jwtDecoder;

	@Nested
	class ByDefault {

		@Test
		void openApiAndSwaggerRequireAuthenticationAndDoNotExist() throws Exception {
			mockMvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
			mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isUnauthorized());
			mockMvc.perform(get("/swagger-ui.html")).andExpect(status().isUnauthorized());

			mockMvc.perform(get("/v3/api-docs").with(jwt())).andExpect(status().isNotFound());
			mockMvc.perform(get("/swagger-ui/index.html").with(jwt())).andExpect(status().isNotFound());
		}

		@Test
		void healthProbesStayPublic() throws Exception {
			mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
		}
	}

	@Nested
	@TestPropertySource(properties = "etp.migration.openapi.enabled=true")
	class WhenExplicitlyEnabled {

		@Test
		void openApiAndSwaggerArePublic() throws Exception {
			mockMvc.perform(get("/v3/api-docs"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.components.securitySchemes.bearer-jwt.scheme").value("bearer"));
			mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
		}

		@Test
		void migrationApisStillRequireAuthentication() throws Exception {
			mockMvc.perform(get("/api/v1/migrations")).andExpect(status().isUnauthorized());
		}
	}

	@Nested
	@ActiveProfiles("dev")
	class InTheDevProfile {

		@Test
		void openApiIsEnabledByDefault() throws Exception {
			mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
		}
	}
}
