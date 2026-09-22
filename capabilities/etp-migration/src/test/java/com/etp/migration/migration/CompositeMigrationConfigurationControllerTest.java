package com.etp.migration.migration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.etp.migration.config.GlobalExceptionHandler;
import com.etp.migration.entity.LoadMode;
import com.etp.migration.entity.MigrationConfigStatus;
import com.etp.migration.entity.TransformationType;
import com.etp.migration.migration.dto.CompositeMigrationConfigurationResponse;
import com.etp.migration.migration.dto.MigrationDefinitionResponse;
import com.etp.migration.migration.dto.MigrationSourceResponse;
import com.etp.migration.transformation.dto.MigrationMappingResponse;
import com.etp.migration.transformation.dto.MigrationMappingRuleResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = CompositeMigrationConfigurationController.class)
@AutoConfigureMockMvc
@Import(GlobalExceptionHandler.class)
class CompositeMigrationConfigurationControllerTest {

	private static final UUID DEFINITION_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
	private static final UUID MAPPING_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
	private static final UUID SOURCE_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private CompositeMigrationConfigurationService compositeMigrationConfigurationService;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	@Test
	void createReturnsCreatedWithLocationAndCompositeBody() throws Exception {
		when(compositeMigrationConfigurationService.create(any())).thenReturn(sampleResponse());

		mockMvc.perform(post("/api/v1/migration-configurations")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content(validBody()))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", "/api/v1/migrations/" + DEFINITION_ID))
				.andExpect(jsonPath("$.definition.id").value(DEFINITION_ID.toString()))
				.andExpect(jsonPath("$.mapping.id").value(MAPPING_ID.toString()))
				.andExpect(jsonPath("$.rules[0].sequenceNo").value(1))
				.andExpect(jsonPath("$.sources[0].id").value(SOURCE_ID.toString()));
	}

	@Test
	void nestedValidationErrorReturnsBadRequest() throws Exception {
		mockMvc.perform(post("/api/v1/migration-configurations")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "definition": {
								    "version": "1.0.0",
								    "sourceType": "FILE",
								    "targetType": "POSTGRESQL"
								  },
								  "mapping": {
								    "name": "map",
								    "version": "1.0.0",
								    "sourceType": "FILE",
								    "targetType": "POSTGRESQL"
								  },
								  "rules": [
								    {
								      "sequenceNo": 1,
								      "targetField": "external_id",
								      "transformationType": "DIRECT"
								    }
								  ],
								  "source": {
								    "name": "csv",
								    "sourceType": "FILE"
								  }
								}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("name is required")));
	}

	@Test
	void unauthenticatedRequestIsRejected() throws Exception {
		mockMvc.perform(post("/api/v1/migration-configurations")
						.contentType(MediaType.APPLICATION_JSON)
						.content(validBody()))
				.andExpect(status().isForbidden());
	}

	private static String validBody() {
		return """
				{
				  "definition": {
				    "name": "addr-import",
				    "version": "1.0.0",
				    "sourceType": "FILE",
				    "targetType": "POSTGRESQL"
				  },
				  "mapping": {
				    "name": "addr-map",
				    "version": "1.0.0",
				    "sourceType": "FILE",
				    "targetType": "POSTGRESQL"
				  },
				  "rules": [
				    {
				      "sequenceNo": 1,
				      "sourceField": "external_id",
				      "targetField": "external_id",
				      "transformationType": "DIRECT",
				      "mandatory": true,
				      "enabled": true
				    }
				  ],
				  "source": {
				    "name": "csv",
				    "sourceType": "FILE",
				    "configuration": {
				      "provider": "FILE_SYSTEM"
				    }
				  }
				}
				""";
	}

	private static CompositeMigrationConfigurationResponse sampleResponse() {
		Instant now = Instant.parse("2026-09-21T00:00:00Z");
		return new CompositeMigrationConfigurationResponse(
				new MigrationDefinitionResponse(
						DEFINITION_ID, "addr-import", "1.0.0", null, "FILE", "POSTGRESQL", null,
						MAPPING_ID, 10_000, LoadMode.BULK, MigrationConfigStatus.DRAFT, Map.of(),
						now, "system", now, "system"),
				new MigrationMappingResponse(
						MAPPING_ID, "addr-map", "1.0.0", "FILE", "POSTGRESQL", null, Map.of(),
						MigrationConfigStatus.DRAFT, now, "system", now, "system"),
				List.of(new MigrationMappingRuleResponse(
						1L, MAPPING_ID, 1, "external_id", "external_id", TransformationType.DIRECT,
						null, null, Map.of(), true, true, now, now)),
				List.of(new MigrationSourceResponse(
						SOURCE_ID, DEFINITION_ID, "csv", "FILE", Map.of(), true, now, "system", now, "system")));
	}
}
