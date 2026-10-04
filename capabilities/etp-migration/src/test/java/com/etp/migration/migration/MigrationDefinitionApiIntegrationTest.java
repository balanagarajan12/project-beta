package com.etp.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.etp.migration.repository.MigrationMappingRuleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MigrationDefinitionApiIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private MigrationMappingRuleRepository ruleRepository;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	@Test
	void createGetAndListMigrationDefinition() throws Exception {
		String uniqueName = "phase4-it-" + System.nanoTime();
		MvcResult created = mockMvc.perform(post("/api/v1/migrations")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "name": "%s",
								  "version": "1.0.0",
								  "sourceType": "FILE",
								  "targetType": "UAM"
								}
								""".formatted(uniqueName)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("DRAFT"))
				.andExpect(jsonPath("$.batchSize").value(10000))
				.andExpect(jsonPath("$.loadMode").value("BULK"))
				.andReturn();

		String id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");

		mockMvc.perform(get("/api/v1/migrations/{id}", id).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value(uniqueName));

		mockMvc.perform(get("/api/v1/migrations").param("page", "0").param("size", "20").with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content").isArray())
				.andExpect(jsonPath("$.size").value(20));
	}

	@Test
	void duplicateNameAndVersionReturnsConflict() throws Exception {
		String uniqueName = "phase4-dup-" + System.nanoTime();
		String body = """
				{
				  "name": "%s",
				  "version": "1.0.0",
				  "sourceType": "FILE",
				  "targetType": "UAM"
				}
				""".formatted(uniqueName);

		mockMvc.perform(post("/api/v1/migrations").with(jwt()).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated());

		mockMvc.perform(post("/api/v1/migrations").with(jwt()).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isConflict());
	}

	@Test
	void openApiDocumentsMigrationDefinitionEndpoints() throws Exception {
		String openApiJson = mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();

		assertThat(openApiJson).contains("/api/v1/migrations");
		assertThat(openApiJson).contains("Create a migration definition");
		assertThat(openApiJson).contains("List migration definitions");
		assertThat(openApiJson).contains("/api/v1/migrations/{migrationId}/sources");
		assertThat(openApiJson).contains("/api/v1/mappings");
		assertThat(openApiJson).contains("/api/v1/mappings/{mappingId}/rules");
	}

	@Test
	void createSourceMappingAndRule() throws Exception {
		String unique = String.valueOf(System.nanoTime());
		MvcResult definition = mockMvc.perform(post("/api/v1/migrations")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "name": "phase5-def-%s",
								  "version": "1.0.0",
								  "sourceType": "FILE",
								  "targetType": "UAM"
								}
								""".formatted(unique)))
				.andExpect(status().isCreated())
				.andReturn();
		String migrationId = com.jayway.jsonpath.JsonPath.read(definition.getResponse().getContentAsString(), "$.id");

		MvcResult source = mockMvc.perform(post("/api/v1/migrations/{migrationId}/sources", migrationId)
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "name": "address-csv",
								  "sourceType": "FILE"
								}
								"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.enabled").value(true))
				.andReturn();
		String sourceId = com.jayway.jsonpath.JsonPath.read(source.getResponse().getContentAsString(), "$.id");

		mockMvc.perform(get("/api/v1/migrations/{migrationId}/sources/{sourceId}", migrationId, sourceId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("address-csv"));

		MvcResult mapping = mockMvc.perform(post("/api/v1/mappings")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "name": "phase5-map-%s",
								  "version": "1.0.0",
								  "sourceType": "FILE",
								  "targetType": "UAM"
								}
								""".formatted(unique)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("DRAFT"))
				.andReturn();
		String mappingId = com.jayway.jsonpath.JsonPath.read(mapping.getResponse().getContentAsString(), "$.id");

		mockMvc.perform(post("/api/v1/mappings")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "name": "phase5-map-%s",
								  "version": "1.0.0",
								  "sourceType": "FILE",
								  "targetType": "UAM"
								}
								""".formatted(unique)))
				.andExpect(status().isConflict());

		mockMvc.perform(post("/api/v1/mappings/{mappingId}/rules", mappingId)
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "sequenceNo": 1,
								  "sourceField": "addr1",
								  "targetField": "streetName",
								  "transformationType": "TRIM"
								}
								"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.enabled").value(true))
				.andExpect(jsonPath("$.mandatory").value(false))
				.andExpect(jsonPath("$.entity").value(org.hamcrest.Matchers.nullValue()));

		mockMvc.perform(post("/api/v1/mappings/{mappingId}/rules", mappingId)
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "sequenceNo": 1,
								  "targetField": "streetName",
								  "transformationType": "DIRECT"
								}
								"""))
				.andExpect(status().isConflict());

		mockMvc.perform(get("/api/v1/mappings/{mappingId}/rules", mappingId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].sequenceNo").value(1))
				.andExpect(jsonPath("$.content[0].entity").value(org.hamcrest.Matchers.nullValue()));
	}

	@Test
	void entityScopedRulesRoundTripAndRejectUnknownEntities() throws Exception {
		String unique = String.valueOf(System.nanoTime());
		String mappingId = createRelationshipMapping(unique, "Customer", "Account");

		mockMvc.perform(post("/api/v1/mappings/{mappingId}/rules", mappingId)
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "sequenceNo": 1,
								  "entity": "Customer",
								  "sourceField": "customer_name",
								  "targetField": "name",
								  "transformationType": "TRIM"
								}
								"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.entity").value("Customer"));

		mockMvc.perform(get("/api/v1/mappings/{mappingId}/rules", mappingId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].entity").value("Customer"));

		assertThat(ruleRepository.findByMapping_IdAndEntityNameAndEnabledTrueOrderBySequenceNoAsc(
				java.util.UUID.fromString(mappingId), "Customer"))
				.singleElement()
				.extracting(com.etp.migration.entity.MigrationMappingRule::getEntityName)
				.isEqualTo("Customer");

		mockMvc.perform(post("/api/v1/mappings/{mappingId}/rules", mappingId)
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "sequenceNo": 2,
								  "sourceField": "customer_name",
								  "targetField": "name",
								  "transformationType": "TRIM"
								}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(
						"Mapping rule entity is required for an ENTITY_RELATIONSHIP mapping."));

		mockMvc.perform(post("/api/v1/mappings/{mappingId}/rules", mappingId)
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "sequenceNo": 3,
								  "entity": "Address",
								  "sourceField": "customer_name",
								  "targetField": "name",
								  "transformationType": "TRIM"
								}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(
						"Mapping rule entity 'Address' is not defined in the migration mapping configuration."));
	}

	private String createRelationshipMapping(String unique, String root, String child) throws Exception {
		MvcResult mapping = mockMvc.perform(post("/api/v1/mappings")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
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
								}
								""".formatted(unique, root, root, child, root, child)))
				.andExpect(status().isCreated())
				.andReturn();
		return com.jayway.jsonpath.JsonPath.read(mapping.getResponse().getContentAsString(), "$.id");
	}

	@Test
	void sourceRequiresExistingDefinition() throws Exception {
		mockMvc.perform(post("/api/v1/migrations/{migrationId}/sources", "99999999-9999-9999-9999-999999999999")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "name": "address-csv",
								  "sourceType": "FILE"
								}
								"""))
				.andExpect(status().isNotFound());
	}
}
