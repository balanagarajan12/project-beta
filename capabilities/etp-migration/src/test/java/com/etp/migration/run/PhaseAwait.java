package com.etp.migration.run;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Submits a phase and polls until the job is terminal, then returns the business GET.
 * The status request does not start the phase.
 */
public final class PhaseAwait {

	private static final Duration TIMEOUT = Duration.ofSeconds(90);
	private static final Duration PAUSE = Duration.ofMillis(30);

	private PhaseAwait() {
	}

	public static ResultActions reconciled(MockMvc mockMvc, Object runId) throws Exception {
		return await(mockMvc, post("/api/v1/migration-runs/{runId}/reconcile", runId).with(jwt()),
				"/api/v1/migration-runs/" + runId + "/reconciliation");
	}

	public static ResultActions reconciled(MockMvc mockMvc, Object runId, String scope) throws Exception {
		return await(mockMvc,
				post("/api/v1/migration-runs/{runId}/reconcile", runId)
						.param("verificationScope", scope)
						.with(jwt()),
				"/api/v1/migration-runs/" + runId + "/reconciliation");
	}

	public static ResultActions prepared(MockMvc mockMvc, Object runId) throws Exception {
		return await(mockMvc,
				post("/api/v1/migration-runs/{runId}/promotion/prepare", runId)
						.contentType(MediaType.APPLICATION_JSON)
						.with(jwt()),
				"/api/v1/migration-runs/" + runId + "/promotion");
	}

	public static ResultActions prepared(MockMvc mockMvc, Object runId, org.springframework.test.web.servlet.RequestBuilder request)
			throws Exception {
		return await(mockMvc, request, "/api/v1/migration-runs/" + runId + "/promotion");
	}

	public static ResultActions promoted(MockMvc mockMvc, Object runId) throws Exception {
		return await(mockMvc,
				post("/api/v1/migration-runs/{runId}/promote", runId).with(jwt()),
				"/api/v1/migration-runs/" + runId + "/promotion");
	}

	public static void untilComplete(MockMvc mockMvc, Object runId, String jobId) throws Exception {
		awaitJob(mockMvc, String.valueOf(runId), jobId);
	}

	private static ResultActions await(MockMvc mockMvc, org.springframework.test.web.servlet.RequestBuilder request, String resultUrl)
			throws Exception {
		String accepted = mockMvc.perform(request)
				.andExpect(status().isAccepted())
				.andReturn()
				.getResponse()
				.getContentAsString();
		String jobId = JsonPath.read(accepted, "$.jobId");
		String runId = JsonPath.read(accepted, "$.runId");
		awaitJob(mockMvc, runId, jobId);
		return mockMvc.perform(get(resultUrl).with(jwt())).andExpect(status().isOk());
	}

	private static void awaitJob(MockMvc mockMvc, String runId, String jobId) throws Exception {
		Instant deadline = Instant.now().plus(TIMEOUT);
		String jobStatus = "";
		while (Instant.now().isBefore(deadline)) {
			String body = mockMvc.perform(get("/api/v1/migration-runs/{runId}/phase-jobs/{jobId}", runId, jobId).with(jwt()))
					.andExpect(status().isOk())
					.andReturn()
					.getResponse()
					.getContentAsString();
			jobStatus = JsonPath.read(body, "$.status");
			if ("COMPLETED".equals(jobStatus)) {
				return;
			}
			if ("FAILED".equals(jobStatus)) {
				throw new AssertionError("Phase job " + jobId + " failed: " + body);
			}
			Thread.sleep(PAUSE.toMillis());
		}
		throw new AssertionError("Phase job " + jobId + " stayed " + jobStatus);
	}
}
