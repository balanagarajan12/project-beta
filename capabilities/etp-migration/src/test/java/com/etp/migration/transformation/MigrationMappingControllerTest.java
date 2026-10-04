package com.etp.migration.transformation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.etp.migration.config.GlobalExceptionHandler;
import com.etp.migration.entity.MigrationConfigStatus;
import com.etp.migration.entity.TransformationType;
import com.etp.migration.migration.DuplicateMigrationMappingException;
import com.etp.migration.migration.DuplicateMappingRuleException;
import com.etp.migration.migration.MappingNotFoundException;
import com.etp.migration.migration.dto.PagedResponse;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = MigrationMappingController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class MigrationMappingControllerTest {

	private static final UUID MAPPING_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private MigrationMappingService migrationMappingService;

	@Test
	void createMappingReturnsCreated() throws Exception {
		when(migrationMappingService.create(any())).thenReturn(sampleMapping());

		mockMvc.perform(post("/api/v1/mappings")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "name": "file-to-uam",
								  "version": "1.0.0",
								  "sourceType": "FILE",
								  "targetType": "UAM"
								}
								"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("DRAFT"))
				.andExpect(jsonPath("$.id").value(MAPPING_ID.toString()));
	}

	@Test
	void createMappingRejectsMissingRequiredFields() throws Exception {
		mockMvc.perform(post("/api/v1/mappings")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "description": "missing fields"
								}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("Validation Failed"))
				.andExpect(jsonPath("$.message").value("Request validation failed"))
				.andExpect(jsonPath("$.errors[*].message", org.hamcrest.Matchers.hasItem("name is required")));
	}

	@Test
	void createMappingRejectsDuplicateNameAndVersion() throws Exception {
		when(migrationMappingService.create(any()))
				.thenThrow(new DuplicateMigrationMappingException("file-to-uam", "1.0.0"));

		mockMvc.perform(post("/api/v1/mappings")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "name": "file-to-uam",
								  "version": "1.0.0",
								  "sourceType": "FILE",
								  "targetType": "UAM"
								}
								"""))
				.andExpect(status().isConflict());
	}

	@Test
	void getMappingReturnsOk() throws Exception {
		when(migrationMappingService.getById(MAPPING_ID)).thenReturn(sampleMapping());

		mockMvc.perform(get("/api/v1/mappings/{id}", MAPPING_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("file-to-uam"));
	}

	@Test
	void listMappingsReturnsPage() throws Exception {
		when(migrationMappingService.list(0, 20))
				.thenReturn(new PagedResponse<>(List.of(sampleMapping()), 0, 20, 1, 1));

		mockMvc.perform(get("/api/v1/mappings"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page").value(0))
				.andExpect(jsonPath("$.size").value(20));
	}

	@Test
	void createRuleReturnsCreated() throws Exception {
		when(migrationMappingService.createRule(eq(MAPPING_ID), any())).thenReturn(sampleRule());

		mockMvc.perform(post("/api/v1/mappings/{mappingId}/rules", MAPPING_ID)
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
				.andExpect(jsonPath("$.targetField").value("streetName"));
	}

	@Test
	void createRuleWhenMappingMissingReturnsNotFound() throws Exception {
		when(migrationMappingService.createRule(eq(MAPPING_ID), any()))
				.thenThrow(new MappingNotFoundException(MAPPING_ID));

		mockMvc.perform(post("/api/v1/mappings/{mappingId}/rules", MAPPING_ID)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "sequenceNo": 1,
								  "targetField": "streetName",
								  "transformationType": "DIRECT"
								}
								"""))
				.andExpect(status().isNotFound());
	}

	@Test
	void createRuleRejectsMissingTargetField() throws Exception {
		mockMvc.perform(post("/api/v1/mappings/{mappingId}/rules", MAPPING_ID)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "sequenceNo": 1,
								  "transformationType": "DIRECT"
								}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("Validation Failed"))
				.andExpect(jsonPath("$.message").value("Request validation failed"))
				.andExpect(jsonPath("$.errors[*].message", org.hamcrest.Matchers.hasItem("targetField is required")));
	}

	@Test
	void createRuleRejectsInvalidTransformationType() throws Exception {
		mockMvc.perform(post("/api/v1/mappings/{mappingId}/rules", MAPPING_ID)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "sequenceNo": 1,
								  "targetField": "streetName",
								  "transformationType": "EXECUTE_SQL"
								}
								"""))
				.andExpect(status().isBadRequest());
	}

	@Test
	void createRuleRejectsInvalidSequenceNumber() throws Exception {
		mockMvc.perform(post("/api/v1/mappings/{mappingId}/rules", MAPPING_ID)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "sequenceNo": 0,
								  "targetField": "streetName",
								  "transformationType": "DIRECT"
								}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("Validation Failed"))
				.andExpect(jsonPath("$.message").value("Request validation failed"))
				.andExpect(jsonPath("$.errors[*].message",
						org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("sequenceNo"))));
	}

	@Test
	void createRuleRejectsDuplicateSequenceNumber() throws Exception {
		when(migrationMappingService.createRule(eq(MAPPING_ID), any()))
				.thenThrow(new DuplicateMappingRuleException(1));

		mockMvc.perform(post("/api/v1/mappings/{mappingId}/rules", MAPPING_ID)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "sequenceNo": 1,
								  "targetField": "streetName",
								  "transformationType": "DIRECT"
								}
								"""))
				.andExpect(status().isConflict());
	}

	@Test
	void listRulesReturnsPage() throws Exception {
		when(migrationMappingService.listRules(MAPPING_ID, 0, 20))
				.thenReturn(new PagedResponse<>(List.of(sampleRule()), 0, 20, 1, 1));

		mockMvc.perform(get("/api/v1/mappings/{mappingId}/rules", MAPPING_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].sequenceNo").value(1));
	}

	private MigrationMappingResponse sampleMapping() {
		Instant now = Instant.parse("2026-09-20T10:00:00Z");
		return new MigrationMappingResponse(
				MAPPING_ID,
				"file-to-uam",
				"1.0.0",
				"FILE",
				"UAM",
				null,
				Map.of(),
				MigrationConfigStatus.DRAFT,
				now,
				"system",
				now,
				"system");
	}

	private MigrationMappingRuleResponse sampleRule() {
		Instant now = Instant.parse("2026-09-20T10:00:00Z");
		return new MigrationMappingRuleResponse(
				10L,
				MAPPING_ID,
				1,
				null,
				"addr1",
				"streetName",
				TransformationType.TRIM,
				null,
				null,
				Map.of(),
				false,
				true,
				now,
				now);
	}
}
