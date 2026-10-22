package com.etp.migration.run;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Polls the existing run-detail endpoint until the run is terminal. The status request does not start execution.
 */
public final class ExecutionAwait {

	private static final Duration TIMEOUT = Duration.ofSeconds(90);
	private static final Duration PAUSE = Duration.ofMillis(30);

	private ExecutionAwait() {
	}

	public static MvcResult untilTerminal(MockMvc mockMvc, String runId) throws Exception {
		Instant deadline = Instant.now().plus(TIMEOUT);
		String status = "";
		MvcResult last = null;
		while (Instant.now().isBefore(deadline)) {
			last = mockMvc.perform(get("/api/v1/migration-runs/{runId}", runId).with(jwt()))
					.andExpect(status().isOk())
					.andReturn();
			status = com.jayway.jsonpath.JsonPath.read(last.getResponse().getContentAsString(), "$.status");
			if (isTerminal(status)) {
				return last;
			}
			Thread.sleep(PAUSE.toMillis());
		}
		throw new AssertionError("Migration run " + runId + " stayed " + status);
	}

	private static boolean isTerminal(String status) {
		return "COMPLETED".equals(status)
				|| "COMPLETED_WITH_ERRORS".equals(status)
				|| "FAILED".equals(status)
				|| "CANCELLED".equals(status);
	}
}
