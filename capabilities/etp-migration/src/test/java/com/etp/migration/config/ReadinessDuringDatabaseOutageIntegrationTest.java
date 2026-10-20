package com.etp.migration.config;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(locations = "file:src/main/resources/application.properties")
class ReadinessDuringDatabaseOutageIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean(name = "controlPlaneDbHealthIndicator")
	private HealthIndicator controlPlaneDb;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	@BeforeEach
	void databaseIsDown() {
		Health down = Health.down().withDetail("error", "jdbc:postgresql://internal-host:5432/secret").build();
		when(controlPlaneDb.health(anyBoolean())).thenReturn(down);
		when(controlPlaneDb.health()).thenReturn(down);
	}

	@Test
	void readinessFailsButLivenessStaysUpWithoutLeakingDetails() throws Exception {
		mockMvc.perform(get("/actuator/health/readiness"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.status").value("DOWN"))
				.andExpect(content().string(Matchers.not(Matchers.containsString("internal-host"))));
		mockMvc.perform(get("/actuator/health/liveness"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));
	}
}
