package com.etp.migration.run;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.etp.migration.config.GlobalExceptionHandler;
import com.etp.migration.entity.MigrationConfigStatus;
import com.etp.migration.entity.MigrationRunStatus;
import com.etp.migration.migration.MigrationDefinitionNotFoundException;
import com.etp.migration.run.dto.MigrationRunResponse;
import java.time.Instant;
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

@WebMvcTest(controllers = MigrationExecutionController.class)
@AutoConfigureMockMvc
@Import(GlobalExceptionHandler.class)
class MigrationExecutionControllerTest {

	private static final UUID DEFINITION_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
	private static final UUID RUN_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private MigrationExecutionService migrationExecutionService;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	@Test
	void executeReturnsAcceptedWithTheQueuedRun() throws Exception {
		when(migrationExecutionService.execute(eq(DEFINITION_ID), any()))
				.thenReturn(sampleRun(MigrationRunStatus.QUEUED));

		mockMvc.perform(post("/api/v1/migrations/{migrationId}/execute", DEFINITION_ID)
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isAccepted())
				.andExpect(header().string("Location", "/api/v1/migration-runs/" + RUN_ID))
				.andExpect(jsonPath("$.id").value(RUN_ID.toString()))
				.andExpect(jsonPath("$.runId").value(RUN_ID.toString()))
				.andExpect(jsonPath("$.status").value("QUEUED"))
				.andExpect(jsonPath("$.message").value("Migration execution accepted."))
				.andExpect(jsonPath("$.statusUrl").value("/api/v1/migration-runs/" + RUN_ID));
	}

	@Test
	void finishedIdempotentRunIsAcceptedWithoutClaimingCompletion() throws Exception {
		when(migrationExecutionService.execute(eq(DEFINITION_ID), any()))
				.thenReturn(sampleRun(MigrationRunStatus.COMPLETED));

		mockMvc.perform(post("/api/v1/migrations/{migrationId}/execute", DEFINITION_ID)
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"idempotencyKey\":\"same-key\"}"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("COMPLETED"))
				.andExpect(jsonPath("$.message").value("Migration run already exists."));
	}

	@Test
	void executeDraftDefinitionReturnsConflict() throws Exception {
		when(migrationExecutionService.execute(eq(DEFINITION_ID), any()))
				.thenThrow(new DefinitionNotActiveException(DEFINITION_ID, MigrationConfigStatus.DRAFT));

		mockMvc.perform(post("/api/v1/migrations/{migrationId}/execute", DEFINITION_ID)
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isConflict());
	}

	@Test
	void executeUnknownDefinitionReturnsNotFound() throws Exception {
		when(migrationExecutionService.execute(eq(DEFINITION_ID), any()))
				.thenThrow(new MigrationDefinitionNotFoundException(DEFINITION_ID));

		mockMvc.perform(post("/api/v1/migrations/{migrationId}/execute", DEFINITION_ID)
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isNotFound());
	}

	@Test
	void unauthenticatedRequestIsRejected() throws Exception {
		mockMvc.perform(post("/api/v1/migrations/{migrationId}/execute", DEFINITION_ID)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isForbidden());
	}

	private static MigrationRunResponse sampleRun(MigrationRunStatus status) {
		Instant now = Instant.parse("2026-09-21T00:00:00Z");
		return new MigrationRunResponse(
				RUN_ID, DEFINITION_ID, 1L, null, status, now, now,
				2L, 2L, 2L, 0L, 0L, 0L, 0L, 0L,
				1, 1, 0, "system", now, now);
	}
}
