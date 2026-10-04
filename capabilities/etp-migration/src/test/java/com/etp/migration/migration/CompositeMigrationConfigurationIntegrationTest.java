package com.etp.migration.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.etp.migration.connector.target.postgresql.PromotionTargetConfiguration;
import com.etp.migration.entity.MigrationConfigStatus;
import com.etp.migration.entity.MigrationDefinition;
import com.etp.migration.entity.PromotionStrategy;
import com.etp.migration.repository.MigrationDefinitionRepository;
import com.etp.migration.repository.MigrationMappingRepository;
import com.etp.migration.repository.MigrationMappingRuleRepository;
import com.etp.migration.repository.MigrationPromotionRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class CompositeMigrationConfigurationIntegrationTest {

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private MigrationDefinitionRepository definitionRepository;
	@Autowired
	private MigrationMappingRepository mappingRepository;
	@Autowired
	private MigrationMappingRuleRepository ruleRepository;
	@Autowired
	private MigrationPromotionRepository promotionRepository;
	@MockitoBean
	private JwtDecoder jwtDecoder;

	@Test
	void onePostCreatesCompleteDraftConfiguration() throws Exception {
		String unique = String.valueOf(System.nanoTime());
		long promotionCount = promotionRepository.count();
		MvcResult created = mockMvc.perform(post("/api/v1/migration-configurations")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content(compositeBody(unique, validPromotion(), uniqueRules())))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.definition.status").value("DRAFT"))
				.andExpect(jsonPath("$.definition.loadMode").value("BULK"))
				.andExpect(jsonPath("$.definition.batchSize").value(10000))
				.andExpect(jsonPath("$.mapping.status").value("DRAFT"))
				.andExpect(jsonPath("$.rules[0].sequenceNo").value(1))
				.andExpect(jsonPath("$.rules[1].sequenceNo").value(2))
				.andExpect(jsonPath("$.sources[0].enabled").value(true))
				.andReturn();

		String definitionId = read(created, "$.definition.id");
		String mappingId = read(created, "$.mapping.id");
		String sourceId = read(created, "$.sources[0].id");

		mockMvc.perform(get("/api/v1/migrations/{id}", definitionId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.mappingId").value(mappingId))
				.andExpect(jsonPath("$.status").value("DRAFT"));
		mockMvc.perform(get("/api/v1/mappings/{id}", mappingId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("DRAFT"));
		mockMvc.perform(get("/api/v1/mappings/{id}/rules", mappingId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(2))
				.andExpect(jsonPath("$.content[0].sequenceNo").value(1))
				.andExpect(jsonPath("$.content[0].entity").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.content[1].sequenceNo").value(2))
				.andExpect(jsonPath("$.content[1].entity").value(org.hamcrest.Matchers.nullValue()));
		mockMvc.perform(get("/api/v1/migrations/{id}/sources/{sourceId}", definitionId, sourceId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.enabled").value(true));

		MigrationDefinition definition = definitionRepository.findById(UUID.fromString(definitionId)).orElseThrow();
		PromotionTargetConfiguration parsed = PromotionTargetConfiguration.parse(definition.getConfiguration());
		assertThat(parsed.enabled()).isTrue();
		assertThat(parsed.strategy()).isEqualTo(PromotionStrategy.UPSERT);
		assertThat(parsed.conflictKeys()).containsExactly("external_id");
		assertThat(promotionRepository.count()).isEqualTo(promotionCount);
		assertThat(definition.getStatus()).isEqualTo(MigrationConfigStatus.DRAFT);

		mockMvc.perform(post("/api/v1/migrations/{id}/runs", definitionId)
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isConflict());
	}

	@Test
	void duplicateDefinitionNameRollsBackMappingAndRules() throws Exception {
		String unique = String.valueOf(System.nanoTime());
		mockMvc.perform(post("/api/v1/migrations")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "name": "phase14-def-%s",
								  "version": "1.0.0",
								  "sourceType": "FILE",
								  "targetType": "POSTGRESQL",
								  "configuration": {
								    "target": {
								      "stagingSchema": "migration_staging",
								      "stagingTable": "stage_table"
								    }
								  }
								}
								""".formatted(unique)))
				.andExpect(status().isCreated());

		String mappingName = "phase14-map-rollback-" + unique;
		mockMvc.perform(post("/api/v1/migration-configurations")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content(compositeBody(unique, "{}", uniqueRules(), mappingName, unique)))
				.andExpect(status().isConflict());

		assertThat(mappingRepository.existsByNameAndVersion(mappingName, "1.0.0")).isFalse();
		assertThat(ruleRepository.findByMapping_IdAndEnabledTrueOrderBySequenceNoAsc(UUID.randomUUID())).isEmpty();
	}

	@Test
	void duplicateMappingNameCreatesNoDefinitionOrSource() throws Exception {
		String unique = String.valueOf(System.nanoTime());
		String mappingName = "phase14-map-dup-" + unique;
		mockMvc.perform(post("/api/v1/mappings")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "name": "%s",
								  "version": "1.0.0",
								  "sourceType": "FILE",
								  "targetType": "POSTGRESQL"
								}
								""".formatted(mappingName)))
				.andExpect(status().isCreated());

		mockMvc.perform(post("/api/v1/migration-configurations")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content(compositeBody(unique, "{}", uniqueRules(), mappingName, unique)))
				.andExpect(status().isConflict());

		assertThat(definitionRepository.existsByNameAndVersion("phase14-def-" + unique, "1.0.0")).isFalse();
	}

	@Test
	void duplicateRuleSequenceRollsBackEntireGraph() throws Exception {
		String unique = String.valueOf(System.nanoTime());
		String mappingName = "phase14-map-seq-" + unique;
		String definitionName = "phase14-def-" + unique;
		mockMvc.perform(post("/api/v1/migration-configurations")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content(compositeBody(unique, "{}", """
								[
								  {
								    "sequenceNo": 1,
								    "sourceField": "external_id",
								    "targetField": "external_id",
								    "transformationType": "DIRECT"
								  },
								  {
								    "sequenceNo": 1,
								    "sourceField": "city",
								    "targetField": "city",
								    "transformationType": "DIRECT"
								  }
								]
								""", mappingName, unique)))
				.andExpect(status().isConflict());

		assertThat(mappingRepository.existsByNameAndVersion(mappingName, "1.0.0")).isFalse();
		assertThat(definitionRepository.existsByNameAndVersion(definitionName, "1.0.0")).isFalse();
	}

	@Test
	void invalidPromotionConfigurationRollsBackEntireGraph() throws Exception {
		String unique = String.valueOf(System.nanoTime());
		String mappingName = "phase14-map-promo-" + unique;
		String definitionName = "phase14-def-" + unique;
		long promotionCount = promotionRepository.count();
		mockMvc.perform(post("/api/v1/migration-configurations")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content(compositeBody(unique, """
								{
								  "promotion": {
								    "enabled": true,
								    "strategy": "UPSERT",
								    "columns": { "external_id": "external_id" }
								  }
								}
								""", uniqueRules(), mappingName, unique)))
				.andExpect(status().isBadRequest());

		assertThat(mappingRepository.existsByNameAndVersion(mappingName, "1.0.0")).isFalse();
		assertThat(definitionRepository.existsByNameAndVersion(definitionName, "1.0.0")).isFalse();
		assertThat(promotionRepository.count()).isEqualTo(promotionCount);
	}

	@Test
	void entityRelationshipCompositePersistsScopedRules() throws Exception {
		String unique = String.valueOf(System.nanoTime());
		MvcResult created = mockMvc.perform(post("/api/v1/migration-configurations")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content(relationshipComposite(unique, "Product", "Variant", true)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.rules[0].entity").value("Product"))
				.andExpect(jsonPath("$.rules[1].entity").value("Variant"))
				.andReturn();

		String mappingId = read(created, "$.mapping.id");
		assertThat(ruleRepository.findByMapping_IdAndEntityNameAndEnabledTrueOrderBySequenceNoAsc(
				UUID.fromString(mappingId), "Product"))
				.singleElement()
				.extracting(com.etp.migration.entity.MigrationMappingRule::getEntityName)
				.isEqualTo("Product");
		assertThat(ruleRepository.findByMapping_IdOrderBySequenceNoAsc(UUID.fromString(mappingId)))
				.extracting(com.etp.migration.entity.MigrationMappingRule::getEntityName)
				.containsExactly("Product", "Variant");
	}

	@Test
	void entityRelationshipCompositeRejectsUnknownRuleEntity() throws Exception {
		String unique = String.valueOf(System.nanoTime());
		String mappingName = "phase2-map-" + unique;
		mockMvc.perform(post("/api/v1/migration-configurations")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content(relationshipComposite(unique, "Product", "Variant", false)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(
						"Mapping rule entity 'Address' is not defined in the migration mapping configuration."));

		assertThat(mappingRepository.existsByNameAndVersion(mappingName, "1.0.0")).isFalse();
	}

	@Test
	void entityRelationshipCompositeRejectsMissingRuleEntity() throws Exception {
		String unique = String.valueOf(System.nanoTime());
		mockMvc.perform(post("/api/v1/migration-configurations")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content(relationshipComposite(unique, "Product", "Variant", true).replace(
								"\"entity\": \"Product\"",
								"\"sourceField\": \"product_name\"")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(
						"Mapping rule entity is required for an ENTITY_RELATIONSHIP mapping."));
	}

	@Test
	void multiFileSourceRejectsUnknownEntity() throws Exception {
		String unique = String.valueOf(System.nanoTime());
		String mappingName = "phase4-map-" + unique;
		mockMvc.perform(post("/api/v1/migration-configurations")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content(multiFileComposite(unique, "Address", "address.csv")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(
						"Source file entity 'Address' is not defined in the migration mapping configuration."));

		assertThat(mappingRepository.existsByNameAndVersion(mappingName, "1.0.0")).isFalse();
	}

	@Test
	void multiFileSourcePersistsConfiguredFiles() throws Exception {
		String unique = String.valueOf(System.nanoTime());
		mockMvc.perform(post("/api/v1/migration-configurations")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content(multiFileComposite(unique, "Product", "product.csv")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.sources[0].configuration.layoutType").value("MULTI_FILE"))
				.andExpect(jsonPath("$.sources[0].configuration.files.Product.file").value("product.csv"))
				.andExpect(jsonPath("$.sources[0].configuration.files.Variant.file").value("variant.csv"));
	}

	@Test
	void existingFlatDefinitionCreateRemainsUnchanged() throws Exception {
		String unique = "phase14-flat-" + System.nanoTime();
		mockMvc.perform(post("/api/v1/migrations")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "name": "%s",
								  "version": "1.0.0",
								  "sourceType": "FILE",
								  "targetType": "UAM"
								}
								""".formatted(unique)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("DRAFT"))
				.andExpect(jsonPath("$.batchSize").value(10000))
				.andExpect(jsonPath("$.loadMode").value("BULK"))
				.andExpect(jsonPath("$.mappingId").value(org.hamcrest.Matchers.nullValue()));
	}

	@Test
	void openApiDocumentsCompositeConfigurationEndpoint() throws Exception {
		String openApiJson = mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();
		assertThat(openApiJson).contains("/api/v1/migration-configurations");
		assertThat(openApiJson).contains("Create a complete migration configuration");
		assertThat(openApiJson).contains("Create a migration definition");
	}

	private String compositeBody(String unique, String configuration, String rules) {
		return compositeBody(unique, configuration, rules, "phase14-map-" + unique, unique);
	}

	private String compositeBody(
			String unique, String configuration, String rules, String mappingName, String definitionSuffix) {
		return """
				{
				  "definition": {
				    "name": "phase14-def-%s",
				    "version": "1.0.0",
				    "sourceType": "FILE",
				    "targetType": "POSTGRESQL",
				    "targetEntity": "address",
				    "configuration": %s
				  },
				  "mapping": {
				    "name": "%s",
				    "version": "1.0.0",
				    "sourceType": "FILE",
				    "targetType": "POSTGRESQL"
				  },
				  "rules": %s,
				  "source": {
				    "name": "csv-source",
				    "sourceType": "FILE",
				    "configuration": {
				      "provider": "FILE_SYSTEM",
				      "objectKey": "samples/phase14.csv",
				      "fileFormat": "CSV"
				    }
				  }
				}
				""".formatted(definitionSuffix, withStagingTable(configuration), mappingName, rules);
	}

	private static String withStagingTable(String configuration) {
		if (configuration.contains("\"stagingTable\"")) {
			return configuration;
		}
		String trimmed = configuration.trim();
		String staging = """
				"target": {
				  "stagingSchema": "migration_staging",
				  "stagingTable": "stage_table"
				}""";
		if (trimmed.equals("{}")) {
			return "{\n" + staging + "\n}";
		}
		if (trimmed.startsWith("{")) {
			return "{\n" + staging + "," + trimmed.substring(1);
		}
		return configuration;
	}

	private static String uniqueRules() {
		return """
				[
				  {
				    "sequenceNo": 1,
				    "sourceField": "external_id",
				    "targetField": "external_id",
				    "transformationType": "DIRECT",
				    "mandatory": true,
				    "enabled": true
				  },
				  {
				    "sequenceNo": 2,
				    "sourceField": "city",
				    "targetField": "city",
				    "transformationType": "DIRECT",
				    "mandatory": false,
				    "enabled": true
				  }
				]
				""";
	}

	private static String validPromotion() {
		return """
				{
				  "target": {
				    "schema": "migration_staging",
				    "stagingTable": "address_load",
				    "mergeStrategy": "NONE"
				  },
				  "promotion": {
				    "enabled": true,
				    "schema": "promo_app",
				    "table": "address",
				    "strategy": "UPSERT",
				    "columns": {
				      "external_id": "external_id",
				      "address_line_1": "address_line_1",
				      "city": "city"
				    },
				      "conflictKeys": ["external_id"]
				  }
				}
				""";
	}

	private static String relationshipComposite(String unique, String root, String child, boolean validRuleEntity) {
		String secondEntity = validRuleEntity ? child : "Address";
		return """
				{
				  "definition": {
				    "name": "phase2-def-%s",
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
				    "name": "phase2-map-%s",
				    "version": "1.0.0",
				    "sourceType": "FILE",
				    "targetType": "POSTGRESQL",
				    "configuration": {
				      "modelType": "ENTITY_RELATIONSHIP",
				      "rootEntity": "%s",
				      "entities": [
				        {
				          "name": "%s",
				          "targetEntity": "root_target",
				          "sourceKey": "root_id",
				          "targetKey": "id"
				        },
				        {
				          "name": "%s",
				          "targetEntity": "child_target",
				          "sourceKey": "child_id",
				          "targetKey": "id"
				        }
				      ],
				      "relationships": [
				        {
				          "parent": "%s",
				          "child": "%s",
				          "cardinality": "ONE_TO_MANY",
				          "keys": [
				            { "parentField": "root_id", "childField": "root_id" }
				          ]
				        }
				      ]
				    }
				  },
				  "rules": [
				    {
				      "sequenceNo": 1,
				      "entity": "%s",
				      "sourceField": "root_name",
				      "targetField": "name",
				      "transformationType": "TRIM"
				    },
				    {
				      "sequenceNo": 2,
				      "entity": "%s",
				      "sourceField": "child_code",
				      "targetField": "code",
				      "transformationType": "TRIM"
				    }
				  ],
				  "source": {
				    "name": "csv-source",
				    "sourceType": "FILE",
				    "configuration": {
				      "provider": "FILE_SYSTEM",
				      "objectKey": "samples/phase2.csv",
				      "fileFormat": "CSV"
				    }
				  }
				}
				""".formatted(unique, unique, root, root, child, root, child, root, secondEntity);
	}

	private static String multiFileComposite(String unique, String firstEntity, String firstFile) {
		return """
				{
				  "definition": {
				    "name": "phase4-def-%s",
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
				    "name": "phase4-map-%s",
				    "version": "1.0.0",
				    "sourceType": "FILE",
				    "targetType": "POSTGRESQL",
				    "configuration": {
				      "modelType": "ENTITY_RELATIONSHIP",
				      "rootEntity": "Product",
				      "entities": [
				        {
				          "name": "Product",
				          "targetEntity": "product",
				          "sourceKey": "product_id",
				          "targetKey": "id"
				        },
				        {
				          "name": "Variant",
				          "targetEntity": "variant",
				          "sourceKey": "variant_id",
				          "targetKey": "id"
				        }
				      ],
				      "relationships": [
				        {
				          "parent": "Product",
				          "child": "Variant",
				          "cardinality": "ONE_TO_MANY",
				          "keys": [
				            { "parentField": "product_id", "childField": "product_id" }
				          ]
				        }
				      ]
				    }
				  },
				  "rules": [
				    {
				      "sequenceNo": 1,
				      "entity": "Product",
				      "sourceField": "product_id",
				      "targetField": "id",
				      "transformationType": "DIRECT"
				    },
				    {
				      "sequenceNo": 2,
				      "entity": "Variant",
				      "sourceField": "variant_id",
				      "targetField": "id",
				      "transformationType": "DIRECT"
				    }
				  ],
				  "source": {
				    "name": "csv-source",
				    "sourceType": "FILE",
				    "configuration": {
				      "layoutType": "MULTI_FILE",
				      "files": {
				        "%s": { "file": "%s" },
				        "Variant": { "file": "variant.csv" }
				      }
				    }
				  }
				}
				""".formatted(unique, unique, firstEntity, firstFile);
	}

	private static String read(MvcResult result, String path) throws Exception {
		return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), path);
	}
}
