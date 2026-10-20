package com.etp.migration.run;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.etp.migration.batch.BatchCompletionCounters;
import com.etp.migration.batch.MigrationBatchQueueService;
import com.etp.migration.entity.MigrationBatch;
import com.etp.migration.entity.MigrationConfigStatus;
import com.etp.migration.entity.MigrationDefinition;
import com.etp.migration.repository.MigrationBatchRepository;
import com.etp.migration.repository.MigrationDefinitionRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import javax.sql.DataSource;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
class MigrationRunExecutionIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private MigrationDefinitionRepository migrationDefinitionRepository;

	@Autowired
	private DataSource dataSource;

	@Autowired
	private MigrationRunService migrationRunService;

	@Autowired
	private MigrationBatchQueueService migrationBatchQueueService;

	@Autowired
	private MigrationBatchRepository migrationBatchRepository;

	@Autowired
	private TransactionTemplate transactionTemplate;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	@Test
	void createRunRequiresActiveDefinition() throws Exception {
		String draftId = createDefinition("DRAFT");
		mockMvc.perform(post("/api/v1/migrations/{id}/runs", draftId)
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isConflict());

		String inactiveId = createDefinition("INACTIVE");
		activate(inactiveId, MigrationConfigStatus.INACTIVE);
		mockMvc.perform(post("/api/v1/migrations/{id}/runs", inactiveId)
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isConflict());
	}

	@Test
	void createGetListCancelAndIdempotentRun() throws Exception {
		String definitionId = createActiveDefinition();
		String body = """
				{
				  "idempotencyKey": "idem-%s"
				}
				""".formatted(System.nanoTime());

		MvcResult first = mockMvc.perform(post("/api/v1/migrations/{id}/runs", definitionId)
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("QUEUED"))
				.andExpect(jsonPath("$.runNumber").value(1))
				.andReturn();
		String runId = readId(first);

		mockMvc.perform(post("/api/v1/migrations/{id}/runs", definitionId)
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").value(runId))
				.andExpect(jsonPath("$.runNumber").value(1));

		mockMvc.perform(get("/api/v1/migration-runs/{runId}", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.migrationDefinitionId").value(definitionId));

		mockMvc.perform(get("/api/v1/migrations/{id}/runs", definitionId).param("page", "0").param("size", "20").with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].id").value(runId));

		mockMvc.perform(post("/api/v1/migration-runs/{runId}/cancel", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CANCELLED"));

		mockMvc.perform(post("/api/v1/migration-runs/{runId}/cancel", runId).with(jwt()))
				.andExpect(status().isConflict());
	}

	@Test
	void unknownRunReturnsNotFound() throws Exception {
		mockMvc.perform(get("/api/v1/migration-runs/{runId}", "99999999-9999-9999-9999-999999999999").with(jwt()))
				.andExpect(status().isNotFound());
	}

	@Test
	void concurrentIdempotentCreateReturnsSameRun() throws Exception {
		String definitionId = createActiveDefinition();
		String key = "concurrent-" + System.nanoTime();
		var request = new com.etp.migration.run.dto.CreateMigrationRunRequest(key, null, null);
		CompletableFuture<UUID> first = CompletableFuture.supplyAsync(() ->
				migrationRunService.create(UUID.fromString(definitionId), request).id());
		CompletableFuture<UUID> second = CompletableFuture.supplyAsync(() ->
				migrationRunService.create(UUID.fromString(definitionId), request).id());
		UUID left = first.get(20, TimeUnit.SECONDS);
		UUID right = second.get(20, TimeUnit.SECONDS);
		org.assertj.core.api.Assertions.assertThat(left).isEqualTo(right);
	}

	@Test
	void planClaimLeaseCompleteFailRetryAndListBatches() throws Exception {
		String definitionId = createActiveDefinition();
		MvcResult created = mockMvc.perform(post("/api/v1/migrations/{id}/runs", definitionId)
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isCreated())
				.andReturn();
		UUID runId = UUID.fromString(readId(created));

		transactionTemplate.executeWithoutResult(status -> migrationRunService.planBatches(runId, 25_000));

		mockMvc.perform(get("/api/v1/migration-runs/{runId}/batches", runId)
						.param("page", "0")
						.param("size", "20")
						.with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(3))
				.andExpect(jsonPath("$.content[0].batchNumber").value(1))
				.andExpect(jsonPath("$.content[0].status").value("PENDING"));

		Optional<MigrationBatch> firstClaim = transactionTemplate.execute(
				status -> migrationBatchQueueService.claimNextBatchForRun(runId, "worker-a"));
		org.assertj.core.api.Assertions.assertThat(firstClaim).isPresent();
		MigrationBatch claimed = firstClaim.orElseThrow();
		org.assertj.core.api.Assertions.assertThat(claimed.getStatus().name()).isEqualTo("CLAIMED");
		org.assertj.core.api.Assertions.assertThat(claimed.getWorkerId()).isEqualTo("worker-a");
		org.assertj.core.api.Assertions.assertThat(claimed.getAttemptCount()).isEqualTo(1);
		org.assertj.core.api.Assertions.assertThat(claimed.getLeaseUntil()).isAfter(Instant.now());

		Optional<MigrationBatch> steal = transactionTemplate.execute(
				status -> migrationBatchQueueService.claimNextBatchForRun(runId, "worker-b"));
		org.assertj.core.api.Assertions.assertThat(steal).isPresent();
		org.assertj.core.api.Assertions.assertThat(steal.orElseThrow().getId()).isNotEqualTo(claimed.getId());

		transactionTemplate.executeWithoutResult(status -> {
			MigrationBatch locked = migrationBatchRepository.findById(claimed.getId()).orElseThrow();
			locked.setLeaseUntil(Instant.now().minus(1, ChronoUnit.MINUTES));
		});

		Optional<MigrationBatch> reclaimed = transactionTemplate.execute(
				status -> migrationBatchQueueService.claimNextBatchForRun(runId, "worker-c"));
		org.assertj.core.api.Assertions.assertThat(reclaimed).isPresent();
		org.assertj.core.api.Assertions.assertThat(reclaimed.orElseThrow().getId()).isEqualTo(claimed.getId());
		org.assertj.core.api.Assertions.assertThat(reclaimed.orElseThrow().getWorkerId()).isEqualTo("worker-c");
		org.assertj.core.api.Assertions.assertThat(reclaimed.orElseThrow().getAttemptCount()).isEqualTo(2);

		Long batchId = claimed.getId();
		transactionTemplate.executeWithoutResult(status -> migrationBatchQueueService.beginProcessing(batchId));
		transactionTemplate.executeWithoutResult(status -> migrationBatchQueueService.completeBatch(
				batchId,
				new BatchCompletionCounters(10000, 10000, 0, 0, 0, 0, 0),
				false));

		Optional<MigrationBatch> afterComplete = transactionTemplate.execute(
				status -> migrationBatchRepository.findById(batchId));
		org.assertj.core.api.Assertions.assertThat(afterComplete.orElseThrow().getStatus().name()).isEqualTo("COMPLETED");

		Long failedId = steal.orElseThrow().getId();
		transactionTemplate.executeWithoutResult(status -> migrationBatchQueueService.beginProcessing(failedId));
		transactionTemplate.executeWithoutResult(status -> migrationBatchQueueService.failBatch(failedId, "boom"));
		transactionTemplate.executeWithoutResult(status -> {
			Optional<MigrationBatch> third = migrationBatchQueueService.claimNextBatchForRun(runId, "worker-d");
			third.ifPresent(batch -> {
				migrationBatchQueueService.beginProcessing(batch.getId());
				migrationBatchQueueService.failBatch(batch.getId(), "boom-2");
			});
		});

		mockMvc.perform(get("/api/v1/migration-runs/{runId}", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("FAILED"));

		mockMvc.perform(post("/api/v1/migration-runs/{runId}/retry", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("QUEUED"));

		mockMvc.perform(get("/api/v1/migration-runs/{runId}/batches", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[?(@.status=='COMPLETED')]").isNotEmpty())
				.andExpect(jsonPath("$.content[?(@.status=='RETRY_PENDING')]").isNotEmpty());

		mockMvc.perform(post("/api/v1/migration-runs/{runId}/retry", runId).with(jwt()))
				.andExpect(status().isConflict());
	}

	@Test
	void cancelRunningRunRequestsCancellation() throws Exception {
		String definitionId = createActiveDefinition();
		MvcResult created = mockMvc.perform(post("/api/v1/migrations/{id}/runs", definitionId)
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isCreated())
				.andReturn();
		UUID runId = UUID.fromString(readId(created));
		transactionTemplate.executeWithoutResult(status -> migrationRunService.planBatches(runId, 10_000));
		transactionTemplate.executeWithoutResult(status -> migrationBatchQueueService.claimNextBatchForRun(runId, "worker-running"));

		mockMvc.perform(get("/api/v1/migration-runs/{runId}", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("RUNNING"));

		mockMvc.perform(post("/api/v1/migration-runs/{runId}/cancel", runId).with(jwt()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CANCEL_REQUESTED"));
	}

	@Test
	void listAllRunsOrdersByStartOrCreationAcrossDefinitions() throws Exception {
		mockMvc.perform(get("/api/v1/migration-runs"))
				.andExpect(status().isUnauthorized());

		String firstDefinition = createActiveDefinition();
		String secondDefinition = createActiveDefinition();
		UUID createdLatest = createRun(firstDefinition);
		UUID startedLatest = createRun(firstDefinition);
		UUID otherDefinition = createRun(secondDefinition);
		stamp(createdLatest, Instant.parse("2099-01-03T00:00:00Z"), null);
		stamp(startedLatest, Instant.parse("2099-01-01T00:00:00Z"), Instant.parse("2099-01-04T00:00:00Z"));
		stamp(otherDefinition, Instant.parse("2099-01-02T00:00:00Z"), Instant.parse("2099-01-02T00:00:00Z"));

		mockMvc.perform(get("/api/v1/migration-runs")
						.with(jwt())
						.param("page", "0")
						.param("size", "2"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page").value(0))
				.andExpect(jsonPath("$.size").value(2))
				.andExpect(jsonPath("$.content[0].id").value(startedLatest.toString()))
				.andExpect(jsonPath("$.content[0].migrationDefinitionId").value(firstDefinition))
				.andExpect(jsonPath("$.content[0].startedAt").value("2099-01-04T00:00:00Z"))
				.andExpect(jsonPath("$.content[1].id").value(createdLatest.toString()))
				.andExpect(jsonPath("$.content[1].migrationDefinitionId").value(firstDefinition))
				.andExpect(jsonPath("$.totalElements").value(greaterThanOrEqualTo(3)))
				.andExpect(jsonPath("$.totalPages").value(greaterThanOrEqualTo(2)));

		mockMvc.perform(get("/api/v1/migration-runs")
						.with(jwt())
						.param("page", "1")
						.param("size", "2"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page").value(1))
				.andExpect(jsonPath("$.content[0].id").value(otherDefinition.toString()))
				.andExpect(jsonPath("$.content[0].migrationDefinitionId").value(secondDefinition));

		mockMvc.perform(get("/api/v1/migration-runs")
						.with(jwt())
						.param("page", "-1")
						.param("size", "500"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page").value(0))
				.andExpect(jsonPath("$.size").value(100));

		mockMvc.perform(get("/api/v1/migration-runs").with(jwt()).param("size", "0"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.size").value(20));

		mockMvc.perform(get("/api/v1/migration-runs")
						.with(jwt())
						.param("migrationDefinitionId", secondDefinition))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].id").value(otherDefinition.toString()))
				.andExpect(jsonPath("$.content[0].migrationDefinitionId").value(secondDefinition))
				.andExpect(jsonPath("$.content[?(@.migrationDefinitionId=='" + firstDefinition + "')]").isEmpty());

		mockMvc.perform(get("/api/v1/migration-runs")
						.with(jwt())
						.param("migrationDefinitionId", "99999999-9999-9999-9999-999999999999"))
				.andExpect(status().isNotFound());
	}

	@Test
	void retryCompletedRunIsRejected() throws Exception {
		String definitionId = createActiveDefinition();
		MvcResult created = mockMvc.perform(post("/api/v1/migrations/{id}/runs", definitionId)
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isCreated())
				.andReturn();
		String runId = readId(created);
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/cancel", runId).with(jwt()))
				.andExpect(status().isOk());
		mockMvc.perform(post("/api/v1/migration-runs/{runId}/retry", runId).with(jwt()))
				.andExpect(status().isConflict());
	}

	private String createActiveDefinition() throws Exception {
		String id = createDefinition("ACTIVE");
		activate(id, MigrationConfigStatus.ACTIVE);
		return id;
	}

	private String createDefinition(String suffix) throws Exception {
		MvcResult created = mockMvc.perform(post("/api/v1/migrations")
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "name": "phase6-%s-%s",
								  "version": "1.0.0",
								  "sourceType": "FILE",
								  "targetType": "UAM"
								}
								""".formatted(suffix.toLowerCase(), System.nanoTime())))
				.andExpect(status().isCreated())
				.andReturn();
		return readId(created);
	}

	private void activate(String definitionId, MigrationConfigStatus status) {
		transactionTemplate.executeWithoutResult(tx -> {
			MigrationDefinition definition = migrationDefinitionRepository.findById(UUID.fromString(definitionId)).orElseThrow();
			definition.setStatus(status);
		});
	}

	private UUID createRun(String definitionId) throws Exception {
		MvcResult created = mockMvc.perform(post("/api/v1/migrations/{id}/runs", definitionId)
						.with(jwt())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isCreated())
				.andReturn();
		return UUID.fromString(readId(created));
	}

	private void stamp(UUID runId, Instant createdAt, Instant startedAt) throws Exception {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"UPDATE migration_run SET created_at = ?, started_at = ? WHERE id = ?")) {
			statement.setObject(1, java.sql.Timestamp.from(createdAt));
			if (startedAt == null) {
				statement.setNull(2, java.sql.Types.TIMESTAMP);
			} else {
				statement.setObject(2, java.sql.Timestamp.from(startedAt));
			}
			statement.setObject(3, runId);
			statement.executeUpdate();
		}
	}

	private String readId(MvcResult result) throws Exception {
		return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.id");
	}
}
