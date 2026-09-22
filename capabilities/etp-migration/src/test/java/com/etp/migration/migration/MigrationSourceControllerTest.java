package com.etp.migration.migration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.etp.migration.config.GlobalExceptionHandler;
import com.etp.migration.migration.dto.MigrationSourceResponse;
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

@WebMvcTest(controllers = MigrationSourceController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class MigrationSourceControllerTest {

	private static final UUID MIGRATION_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
	private static final UUID SOURCE_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private MigrationSourceService migrationSourceService;

	@Test
	void createSourceReturnsCreated() throws Exception {
		when(migrationSourceService.create(eq(MIGRATION_ID), any())).thenReturn(sampleSource());

		mockMvc.perform(post("/api/v1/migrations/{migrationId}/sources", MIGRATION_ID)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "name": "address-csv",
								  "sourceType": "FILE"
								}
								"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").value(SOURCE_ID.toString()))
				.andExpect(jsonPath("$.enabled").value(true));
	}

	@Test
	void createSourceWhenDefinitionMissingReturnsNotFound() throws Exception {
		when(migrationSourceService.create(eq(MIGRATION_ID), any()))
				.thenThrow(new MigrationDefinitionNotFoundException(MIGRATION_ID));

		mockMvc.perform(post("/api/v1/migrations/{migrationId}/sources", MIGRATION_ID)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "name": "address-csv",
								  "sourceType": "FILE"
								}
								"""))
				.andExpect(status().isNotFound());
	}

	@Test
	void createSourceRejectsMissingName() throws Exception {
		mockMvc.perform(post("/api/v1/migrations/{migrationId}/sources", MIGRATION_ID)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "sourceType": "FILE"
								}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("name is required")));
	}

	@Test
	void createSourceRejectsMissingSourceType() throws Exception {
		mockMvc.perform(post("/api/v1/migrations/{migrationId}/sources", MIGRATION_ID)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "name": "address-csv"
								}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("sourceType is required")));
	}

	@Test
	void listSourcesReturnsPage() throws Exception {
		when(migrationSourceService.list(MIGRATION_ID, 0, 20))
				.thenReturn(new PagedResponse<>(List.of(sampleSource()), 0, 20, 1, 1));

		mockMvc.perform(get("/api/v1/migrations/{migrationId}/sources", MIGRATION_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].name").value("address-csv"))
				.andExpect(jsonPath("$.totalElements").value(1));
	}

	@Test
	void getSourceReturnsOk() throws Exception {
		when(migrationSourceService.getById(MIGRATION_ID, SOURCE_ID)).thenReturn(sampleSource());

		mockMvc.perform(get("/api/v1/migrations/{migrationId}/sources/{sourceId}", MIGRATION_ID, SOURCE_ID))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(SOURCE_ID.toString()));
	}

	private MigrationSourceResponse sampleSource() {
		Instant now = Instant.parse("2026-09-20T10:00:00Z");
		return new MigrationSourceResponse(
				SOURCE_ID,
				MIGRATION_ID,
				"address-csv",
				"FILE",
				Map.of(),
				true,
				now,
				"system",
				now,
				"system");
	}
}
