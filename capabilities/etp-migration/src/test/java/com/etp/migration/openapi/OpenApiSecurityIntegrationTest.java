package com.etp.migration.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class OpenApiSecurityIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	@Test
	void apiDocsAndHealthRemainPublic() throws Exception {
		mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
		mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
	}

	@Test
	void protectedMigrationApisRemainUnauthorizedWithoutJwt() throws Exception {
		mockMvc.perform(get("/api/v1/migrations")).andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/v1/migration-configurations")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void openApiAppliesBearerJwtRequirementToProtectedOperations() throws Exception {
		String openApiJson = mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.components.securitySchemes.bearer-jwt.type").value("http"))
				.andExpect(jsonPath("$.components.securitySchemes.bearer-jwt.scheme").value("bearer"))
				.andExpect(jsonPath("$.components.securitySchemes.bearer-jwt.bearerFormat").value("JWT"))
				.andExpect(jsonPath("$.security[0].bearer-jwt").exists())
				.andReturn()
				.getResponse()
				.getContentAsString();

		assertThat(JsonPath.<List<?>>read(openApiJson, "$.security[0]['bearer-jwt']")).isEmpty();
		assertOperationRequiresBearerJwt(openApiJson, "/api/v1/migrations", "get");
		assertOperationRequiresBearerJwt(openApiJson, "/api/v1/migrations", "post");
		assertOperationRequiresBearerJwt(openApiJson, "/api/v1/migration-configurations", "post");
		assertOperationRequiresBearerJwt(openApiJson, "/api/v1/migration-runs/{runId}/promotion", "get");
		assertOperationRequiresBearerJwt(openApiJson, "/api/v1/migration-runs/{runId}/promotion/batches", "get");
		assertOperationRequiresBearerJwt(openApiJson, "/api/v1/migration-runs/{runId}/errors", "get");
	}

	private static void assertOperationRequiresBearerJwt(String openApiJson, String path, String method) {
		List<?> requirement = JsonPath.read(
				openApiJson,
				"$.paths['" + path + "']." + method + ".security[?(@.bearer-jwt)]");
		assertThat(requirement)
				.as("OpenAPI %s %s must require bearer-jwt", method.toUpperCase(), path)
				.isNotEmpty();
	}
}
