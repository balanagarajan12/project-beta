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
				.andExpect(jsonPath("$.content[1].sequenceNo").value(2));
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
								  "targetType": "POSTGRESQL"
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
				""".formatted(definitionSuffix, configuration, mappingName, rules);
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

	private static String read(MvcResult result, String path) throws Exception {
		return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), path);
	}
}
