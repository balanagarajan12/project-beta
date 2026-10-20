package com.etp.migration.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import com.etp.migration.migration.dto.CompositeMigrationConfigurationRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.mockito.ArgumentCaptor;
import org.hamcrest.Matchers;
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
	void acceptsEntityMappingsAndFieldMappingsWithoutConfigurationObjects() throws Exception {
		ArgumentCaptor<CompositeMigrationConfigurationRequest> captor =
				ArgumentCaptor.forClass(CompositeMigrationConfigurationRequest.class);
		when(compositeMigrationConfigurationService.create(captor.capture())).thenReturn(sampleResponse());

		mockMvc.perform(post("/api/v1/migration-configurations")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "definition": {
								    "name": "addr-import",
								    "version": "1.0.0",
								    "sourceType": "FILE",
								    "targetType": "POSTGRESQL",
								    "batchSize": 100,
								    "loadMode": "BULK",
								    "configuration": {
								      "target": {
								        "stagingSchema": "migration_staging",
								        "stagingTable": "stage_table"
								      }
								    }
								  },
								  "entityMappings": {
								    "name": "addr-map",
								    "version": "1.0.0",
								    "sourceType": "FILE",
								    "targetType": "POSTGRESQL",
								    "description": "addresses",
								    "configuration": {
								      "modelType": "ENTITY_RELATIONSHIP",
								      "rootEntity": "GeographicAddress"
								    }
								  },
								  "fieldMappings": [
								    {
								      "sequenceNo": 2,
								      "entity": "GeographicSubAddress",
								      "sourceField": "unit",
								      "targetField": "sub_unit_number",
								      "transformationType": "DIRECT",
								      "mandatory": false,
								      "enabled": true
								    },
								    {
								      "sequenceNo": 1,
								      "entity": "GeographicAddress",
								      "sourceField": "street",
								      "targetField": "street_name",
								      "transformationType": "TRIM",
								      "mandatory": true,
								      "enabled": true
								    }
								  ],
								  "source": {
								    "name": "csv",
								    "sourceType": "FILE",
								    "configuration": {"provider": "FILE_SYSTEM"}
								  }
								}
								"""))
				.andExpect(status().isCreated());

		CompositeMigrationConfigurationRequest request = captor.getValue();
		assertThat(request.definition().targetEntity()).isNull();
		assertThat(request.mapping().name()).isEqualTo("addr-map");
		assertThat(request.mapping().description()).isEqualTo("addresses");
		assertThat(request.rules()).extracting(rule -> rule.sequenceNo()).containsExactly(2, 1);
		assertThat(request.rules()).allSatisfy(rule -> assertThat(rule.configuration()).isNull());
		assertThat(request.rules().get(1).entity()).isEqualTo("GeographicAddress");
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
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.error").value("Validation Failed"))
				.andExpect(jsonPath("$.message").value("Request validation failed"))
				.andExpect(jsonPath("$.path").value("/api/v1/migration-configurations"))
				.andExpect(jsonPath("$.errors[?(@.field == 'definition.name')].message",
						Matchers.hasItem("name is required")));
	}

	@Test
	void invalidTransformationTypeReturnsFieldError() throws Exception {
		mockMvc.perform(post("/api/v1/migration-configurations")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content(validBody().replace("\"DIRECT\"", "\"IDENTITY\"")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("Validation Failed"))
				.andExpect(jsonPath("$.message").value("Request validation failed"))
				.andExpect(jsonPath("$.errors[0].field").value("rules[0].transformationType"))
				.andExpect(jsonPath("$.errors[0].rejectedValue").value("IDENTITY"))
				.andExpect(jsonPath("$.errors[0].message").value("Unsupported transformation type"))
				.andExpect(jsonPath("$.errors[0].allowedValues", Matchers.hasItems(
						"DIRECT", "TRIM", "UPPERCASE", "LOWERCASE", "DEFAULT", "CONCAT",
						"SPLIT", "LOOKUP", "REGEX", "CONDITIONAL", "DATE_FORMAT", "CUSTOM")))
				.andExpect(content().string(Matchers.not(Matchers.containsString("com.etp"))))
				.andExpect(content().string(Matchers.not(Matchers.containsString("Exception"))));
	}

	@Test
	void invalidLoadModeReturnsFieldError() throws Exception {
		mockMvc.perform(post("/api/v1/migration-configurations")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content(validBody().replace(
								"\"targetType\": \"POSTGRESQL\"",
								"\"targetType\": \"POSTGRESQL\",\n    \"loadMode\": \"FAST\"")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("definition.loadMode"))
				.andExpect(jsonPath("$.errors[0].rejectedValue").value("FAST"))
				.andExpect(jsonPath("$.errors[0].message").value("Unsupported load mode"))
				.andExpect(jsonPath("$.errors[0].allowedValues", Matchers.hasItems("SINGLE", "BATCH", "BULK")));
	}

	@Test
	void multipleInvalidFieldsAreReturnedTogether() throws Exception {
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
				.andExpect(jsonPath("$.errors[*].field", Matchers.hasItems("definition.name", "mapping.name")))
				.andExpect(jsonPath("$.errors.length()", Matchers.greaterThanOrEqualTo(2)));
	}

	@Test
	void missingStagingTableReturnsFieldError() throws Exception {
		mockMvc.perform(post("/api/v1/migration-configurations")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "definition": {
								    "name": "addr-import",
								    "version": "1.0.0",
								    "sourceType": "FILE",
								    "targetType": "POSTGRESQL",
								    "loadMode": "BULK",
								    "configuration": {
								      "target": {
								        "mode": "UAM",
								        "schema": "app",
								        "stagingSchema": "migration_staging"
								      }
								    }
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
				.andExpect(jsonPath("$.errors[0].field").value("definition.configuration.target.stagingTable"))
				.andExpect(jsonPath("$.errors[0].message").value("stagingTable is required for POSTGRESQL target"));
	}

	@Test
	void entityRelationshipConfigurationWithStagingTableIsAccepted() throws Exception {
		when(compositeMigrationConfigurationService.create(any())).thenReturn(sampleResponse());

		mockMvc.perform(post("/api/v1/migration-configurations")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "definition": {
								    "name": "item-import",
								    "version": "1.0.0",
								    "sourceType": "FILE",
								    "targetType": "POSTGRESQL",
								    "loadMode": "BULK",
								    "configuration": {
								      "target": {
								        "stagingSchema": "migration_staging",
								        "stagingTable": "stage_table"
								      }
								    }
								  },
								  "mapping": {
								    "name": "item-map",
								    "version": "1.0.0",
								    "sourceType": "FILE",
								    "targetType": "POSTGRESQL",
								    "configuration": {
								      "modelType": "ENTITY_RELATIONSHIP",
								      "rootEntity": "Item",
								      "entities": [
								        {
								          "name": "Item",
								          "targetEntity": "item",
								          "sourceKey": "item_id",
								          "targetKey": "id"
								        }
								      ],
								      "relationships": []
								    }
								  },
								  "rules": [
								    {
								      "sequenceNo": 1,
								      "entity": "Item",
								      "sourceField": "item_id",
								      "targetField": "id",
								      "transformationType": "DIRECT"
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
								"""))
				.andExpect(status().isCreated());
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
				    "targetType": "POSTGRESQL",
				    "configuration": {
				      "target": {
				        "stagingSchema": "migration_staging",
				        "stagingTable": "stage_table"
				      }
				    }
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
						1L, MAPPING_ID, 1, null, "external_id", "external_id", TransformationType.DIRECT,
						null, null, Map.of(), true, true, now, now)),
				List.of(new MigrationSourceResponse(
						SOURCE_ID, DEFINITION_ID, "csv", "FILE", Map.of(), true, now, "system", now, "system")));
	}
}
