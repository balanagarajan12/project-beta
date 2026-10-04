package com.etp.migration.migration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.etp.migration.config.GlobalExceptionHandler;
import com.etp.migration.entity.LoadMode;
import com.etp.migration.entity.MigrationConfigStatus;
import com.etp.migration.migration.dto.MigrationDefinitionResponse;
import com.etp.migration.migration.dto.PagedResponse;
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

@WebMvcTest(controllers = MigrationDefinitionController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class MigrationDefinitionControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private MigrationDefinitionService migrationDefinitionService;

	@Test
	void createMigrationDefinitionReturnsCreated() throws Exception {
		UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
		when(migrationDefinitionService.create(any())).thenReturn(sampleResponse(id));

		mockMvc.perform(post("/api/v1/migrations")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "name": "uam-address-import",
								  "version": "1.0.0",
								  "sourceType": "FILE",
								  "targetType": "UAM"
								}
								"""))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", "/api/v1/migrations/" + id))
				.andExpect(jsonPath("$.id").value(id.toString()))
				.andExpect(jsonPath("$.name").value("uam-address-import"))
				.andExpect(jsonPath("$.status").value("DRAFT"));
	}

	@Test
	void createRejectsMissingRequiredFields() throws Exception {
		mockMvc.perform(post("/api/v1/migrations")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "description": "missing required fields"
								}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.error").value("Validation Failed"))
				.andExpect(jsonPath("$.message").value("Request validation failed"))
				.andExpect(jsonPath("$.errors[*].message", org.hamcrest.Matchers.hasItem("name is required")));
	}

	@Test
	void createRejectsInvalidBatchSize() throws Exception {
		mockMvc.perform(post("/api/v1/migrations")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "name": "uam-address-import",
								  "version": "1.0.0",
								  "sourceType": "FILE",
								  "targetType": "UAM",
								  "batchSize": 0
								}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("Validation Failed"))
				.andExpect(jsonPath("$.message").value("Request validation failed"))
				.andExpect(jsonPath("$.errors[*].message",
						org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("batchSize"))));
	}

	@Test
	void createRejectsNonExistingMappingId() throws Exception {
		UUID mappingId = UUID.fromString("22222222-2222-2222-2222-222222222222");
		when(migrationDefinitionService.create(any())).thenThrow(new InvalidMappingReferenceException(mappingId));

		mockMvc.perform(post("/api/v1/migrations")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "name": "uam-address-import",
								  "version": "1.0.0",
								  "sourceType": "FILE",
								  "targetType": "UAM",
								  "mappingId": "22222222-2222-2222-2222-222222222222"
								}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Migration mapping not found: " + mappingId));
	}

	@Test
	void createRejectsDuplicateNameAndVersion() throws Exception {
		when(migrationDefinitionService.create(any()))
				.thenThrow(new DuplicateMigrationDefinitionException("uam-address-import", "1.0.0"));

		mockMvc.perform(post("/api/v1/migrations")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "name": "uam-address-import",
								  "version": "1.0.0",
								  "sourceType": "FILE",
								  "targetType": "UAM"
								}
								"""))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.status").value(409));
	}

	@Test
	void getMigrationDefinitionByIdReturnsOk() throws Exception {
		UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
		when(migrationDefinitionService.getById(id)).thenReturn(sampleResponse(id));

		mockMvc.perform(get("/api/v1/migrations/{id}", id))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id.toString()))
				.andExpect(jsonPath("$.name").value("uam-address-import"));
	}

	@Test
	void getUnknownIdReturnsNotFound() throws Exception {
		UUID id = UUID.fromString("99999999-9999-9999-9999-999999999999");
		when(migrationDefinitionService.getById(id)).thenThrow(new MigrationDefinitionNotFoundException(id));

		mockMvc.perform(get("/api/v1/migrations/{id}", id))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value(404));
	}

	@Test
	void listMigrationsReturnsPaginatedResponse() throws Exception {
		UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
		when(migrationDefinitionService.list(0, 20)).thenReturn(
				new PagedResponse<>(List.of(sampleResponse(id)), 0, 20, 1, 1));

		mockMvc.perform(get("/api/v1/migrations").param("page", "0").param("size", "20"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].id").value(id.toString()))
				.andExpect(jsonPath("$.page").value(0))
				.andExpect(jsonPath("$.size").value(20))
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.totalPages").value(1));
	}

	@Test
	void listEnforcesMaximumPageSizeInService() throws Exception {
		when(migrationDefinitionService.list(eq(0), eq(500)))
				.thenReturn(new PagedResponse<>(List.of(), 0, 100, 0, 0));

		mockMvc.perform(get("/api/v1/migrations").param("page", "0").param("size", "500"))
				.andExpect(status().isOk());

		verify(migrationDefinitionService).list(0, 500);
	}

	@Test
	void activateReturnsActiveDefinition() throws Exception {
		UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
		when(migrationDefinitionService.activate(id)).thenReturn(sampleResponse(id, MigrationConfigStatus.ACTIVE));

		mockMvc.perform(post("/api/v1/migrations/{migrationId}/activate", id))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id.toString()))
				.andExpect(jsonPath("$.status").value("ACTIVE"));
	}

	@Test
	void activateUnknownIdReturnsNotFound() throws Exception {
		UUID id = UUID.fromString("99999999-9999-9999-9999-999999999999");
		when(migrationDefinitionService.activate(id)).thenThrow(new MigrationDefinitionNotFoundException(id));

		mockMvc.perform(post("/api/v1/migrations/{migrationId}/activate", id))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value(404));
	}

	@Test
	void activateIncompleteDefinitionReturnsBadRequest() throws Exception {
		UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
		when(migrationDefinitionService.activate(id))
				.thenThrow(new IllegalArgumentException("Migration mapping is not configured"));

		mockMvc.perform(post("/api/v1/migrations/{migrationId}/activate", id))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.message").value("Migration mapping is not configured"));
	}

	private MigrationDefinitionResponse sampleResponse(UUID id) {
		return sampleResponse(id, MigrationConfigStatus.DRAFT);
	}

	private MigrationDefinitionResponse sampleResponse(UUID id, MigrationConfigStatus status) {
		Instant now = Instant.parse("2026-09-20T10:00:00Z");
		return new MigrationDefinitionResponse(
				id,
				"uam-address-import",
				"1.0.0",
				null,
				"FILE",
				"UAM",
				null,
				null,
				10_000,
				LoadMode.BULK,
				status,
				Map.of(),
				now,
				"system",
				now,
				"system");
	}
}
