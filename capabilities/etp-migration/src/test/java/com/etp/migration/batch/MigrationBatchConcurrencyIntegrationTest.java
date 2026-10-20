package com.etp.migration.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.etp.migration.entity.MigrationBatch;
import com.etp.migration.entity.MigrationBatchStatus;
import com.etp.migration.entity.MigrationConfigStatus;
import com.etp.migration.entity.MigrationDefinition;
import com.etp.migration.entity.MigrationRun;
import com.etp.migration.entity.MigrationRunStatus;
import com.etp.migration.repository.MigrationBatchRepository;
import com.etp.migration.repository.MigrationDefinitionRepository;
import com.etp.migration.repository.MigrationRunRepository;
import com.etp.migration.run.MigrationRunService;
import com.etp.migration.run.dto.CreateMigrationRunRequest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class MigrationBatchConcurrencyIntegrationTest {

	private static final int BATCHES = 4;
	private static final int RECORDS_PER_BATCH = 10;

	@Autowired
	private MigrationBatchQueueService queue;

	@Autowired
	private MigrationRunService migrationRunService;

	@Autowired
	private MigrationDefinitionRepository migrationDefinitionRepository;

	@Autowired
	private MigrationRunRepository migrationRunRepository;

	@Autowired
	private MigrationBatchRepository migrationBatchRepository;

	@Autowired
	private TransactionTemplate transactionTemplate;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	@Test
	void concurrentCompletionsKeepEveryRunCounterAndFinishTheRun() throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(BATCHES);
		try {
			for (int round = 0; round < 5; round++) {
				UUID runId = plannedRun();
				List<BatchClaim> claims = new ArrayList<>();
				for (int i = 0; i < BATCHES; i++) {
					String worker = "worker-" + round + "-" + i;
					MigrationBatch claimed = transactionTemplate.execute(
							status -> queue.claimNextBatchForRun(runId, worker).orElseThrow());
					BatchClaim claim = BatchClaim.of(claimed);
					transactionTemplate.executeWithoutResult(status -> queue.beginProcessing(claim));
					claims.add(claim);
				}

				CyclicBarrier start = new CyclicBarrier(BATCHES);
				List<CompletableFuture<Void>> completions = new ArrayList<>();
				for (BatchClaim claim : claims) {
					completions.add(CompletableFuture.runAsync(() -> {
						await(start);
						transactionTemplate.executeWithoutResult(status -> queue.completeBatch(
								claim,
								new BatchCompletionCounters(RECORDS_PER_BATCH, RECORDS_PER_BATCH, 0, 0, 0, 0, 0),
								false));
					}, executor));
				}
				for (CompletableFuture<Void> completion : completions) {
					completion.get(30, TimeUnit.SECONDS);
				}

				MigrationRun run = migrationRunRepository.findById(runId).orElseThrow();
				assertThat(run.getCompletedBatches()).isEqualTo(BATCHES);
				assertThat(run.getProcessedRecords()).isEqualTo((long) BATCHES * RECORDS_PER_BATCH);
				assertThat(run.getSuccessRecords()).isEqualTo((long) BATCHES * RECORDS_PER_BATCH);
				assertThat(run.getStatus()).isEqualTo(MigrationRunStatus.COMPLETED);
			}
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void staleClaimCannotCompleteOrFailAfterTheBatchIsReclaimed() {
		UUID runId = plannedRun();
		MigrationBatch first = transactionTemplate.execute(
				status -> queue.claimNextBatchForRun(runId, "worker-old").orElseThrow());
		BatchClaim stale = BatchClaim.of(first);
		transactionTemplate.executeWithoutResult(status -> queue.beginProcessing(stale));
		transactionTemplate.executeWithoutResult(status -> migrationBatchRepository.findById(stale.batchId())
				.orElseThrow()
				.setLeaseUntil(Instant.now().minus(1, ChronoUnit.MINUTES)));

		MigrationBatch reclaimed = transactionTemplate.execute(
				status -> queue.claimNextBatchForRun(runId, "worker-new").orElseThrow());
		assertThat(reclaimed.getId()).isEqualTo(stale.batchId());
		BatchClaim current = BatchClaim.of(reclaimed);
		assertThat(current.attempt()).isEqualTo(stale.attempt() + 1);

		BatchCompletionCounters counters = new BatchCompletionCounters(
				RECORDS_PER_BATCH, RECORDS_PER_BATCH, 0, 0, 0, 0, 0);
		assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(
				status -> queue.completeBatch(stale, counters, false)))
				.isInstanceOf(BatchClaimLostException.class);
		assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(
				status -> queue.failBatch(stale, "late failure")))
				.isInstanceOf(BatchClaimLostException.class);
		assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> queue.renewLease(stale)))
				.isInstanceOf(BatchClaimLostException.class);

		transactionTemplate.executeWithoutResult(status -> queue.completeBatch(current, counters, false));
		assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(
				status -> queue.completeBatch(current, counters, false)))
				.isInstanceOf(BatchClaimLostException.class);

		MigrationBatch batch = migrationBatchRepository.findById(stale.batchId()).orElseThrow();
		assertThat(batch.getStatus()).isEqualTo(MigrationBatchStatus.COMPLETED);
		MigrationRun run = migrationRunRepository.findById(runId).orElseThrow();
		assertThat(run.getCompletedBatches()).isEqualTo(1);
		assertThat(run.getProcessedRecords()).isEqualTo(RECORDS_PER_BATCH);
		assertThat(run.getFailedBatches()).isZero();
	}

	private UUID plannedRun() {
		MigrationDefinition definition = new MigrationDefinition();
		Instant now = Instant.now();
		definition.setId(UUID.randomUUID());
		definition.setName("batch-concurrency-" + System.nanoTime());
		definition.setVersion("1.0.0");
		definition.setSourceType("FILE");
		definition.setTargetType("UAM");
		definition.setBatchSize(RECORDS_PER_BATCH);
		definition.setStatus(MigrationConfigStatus.ACTIVE);
		definition.setCreatedAt(now);
		definition.setUpdatedAt(now);
		definition.setCreatedBy("test");
		definition.setUpdatedBy("test");
		migrationDefinitionRepository.saveAndFlush(definition);
		UUID runId = migrationRunService.create(
				definition.getId(), new CreateMigrationRunRequest(null, null, null)).id();
		transactionTemplate.executeWithoutResult(
				status -> migrationRunService.planBatches(runId, (long) BATCHES * RECORDS_PER_BATCH));
		return runId;
	}

	private static void await(CyclicBarrier barrier) {
		try {
			barrier.await(10, TimeUnit.SECONDS);
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}
}
