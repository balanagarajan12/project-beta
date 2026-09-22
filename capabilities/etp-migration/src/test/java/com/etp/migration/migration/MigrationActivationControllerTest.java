package com.etp.migration.migration;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.etp.migration.config.GlobalExceptionHandler;
import com.etp.migration.entity.LoadMode;
import com.etp.migration.entity.MigrationConfigStatus;
import com.etp.migration.migration.dto.MigrationDefinitionResponse;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = MigrationDefinitionController.class)
@AutoConfigureMockMvc
@Import(GlobalExceptionHandler.class)
class MigrationActivationControllerTest {

	private static final UUID DEFINITION_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private MigrationDefinitionService migrationDefinitionService;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	@Test
	void activateRequiresAuthentication() throws Exception {
		mockMvc.perform(post("/api/v1/migrations/{migrationId}/activate", DEFINITION_ID))
				.andExpect(status().isForbidden());
	}

	@Test
	void authenticatedActivateReturnsActiveDefinition() throws Exception {
		when(migrationDefinitionService.activate(eq(DEFINITION_ID))).thenReturn(sample());

		mockMvc.perform(post("/api/v1/migrations/{migrationId}/activate", DEFINITION_ID).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(DEFINITION_ID.toString()))
				.andExpect(jsonPath("$.status").value("ACTIVE"));
	}

	private static MigrationDefinitionResponse sample() {
		Instant now = Instant.parse("2026-09-21T00:00:00Z");
		return new MigrationDefinitionResponse(
				DEFINITION_ID,
				"activate-def",
				"1.0.0",
				null,
				"FILE",
				"POSTGRESQL",
				null,
				null,
				10_000,
				LoadMode.BULK,
				MigrationConfigStatus.ACTIVE,
				Map.of(),
				now,
				"system",
				now,
				"system");
	}
}
