package com.etp.migration.performance;

import com.etp.migration.connector.storage.ArtifactChecksumCalculator;
import com.etp.migration.connector.target.postgresql.PostgreSqlTargetClientConfig;
import com.etp.migration.entity.LoadMode;
import com.etp.migration.entity.MigrationBatch;
import com.etp.migration.entity.MigrationBatchStatus;
import com.etp.migration.entity.MigrationConfigStatus;
import com.etp.migration.entity.MigrationDefinition;
import com.etp.migration.entity.StorageProvider;
import com.etp.migration.entity.TransformationType;
import com.etp.migration.migration.MigrationDefinitionService;
import com.etp.migration.migration.MigrationSourceService;
import com.etp.migration.migration.dto.CreateMigrationDefinitionRequest;
import com.etp.migration.migration.dto.CreateMigrationSourceRequest;
import com.etp.migration.processing.MigrationBatchWorker;
import com.etp.migration.processing.PrepareMigrationRunResponse;
import com.etp.migration.processing.ProcessBatchResponse;
import com.etp.migration.processing.SourceInspectionService;
import com.etp.migration.reconciliation.ReconciliationService;
import com.etp.migration.reconciliation.dto.MigrationReconciliationResponse;
import com.etp.migration.repository.MigrationBatchRepository;
import com.etp.migration.repository.MigrationDefinitionRepository;
import com.etp.migration.run.MigrationRunService;
import com.etp.migration.run.dto.CreateMigrationRunRequest;
import com.etp.migration.run.dto.MigrationRunResponse;
import com.etp.migration.transformation.MigrationMappingService;
import com.etp.migration.transformation.dto.CreateMappingRuleRequest;
import com.etp.migration.transformation.dto.CreateMigrationMappingRequest;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.transaction.support.TransactionTemplate;

@TestConfiguration
public class BenchmarkPipeline {

	private final MigrationMappingService mappingService;
	private final MigrationDefinitionService definitionService;
	private final MigrationSourceService sourceService;
	private final MigrationRunService runService;
	private final SourceInspectionService sourceInspectionService;
	private final MigrationBatchWorker batchWorker;
	private final ReconciliationService reconciliationService;
	private final MigrationDefinitionRepository definitionRepository;
	private final TransactionTemplate transactionTemplate;
	private final ArtifactChecksumCalculator checksumCalculator;
	private final MigrationBatchRepository batchRepository;
	private final DataSource targetDataSource;
	private final DataSource controlDataSource;

	BenchmarkPipeline(
			MigrationMappingService mappingService,
			MigrationDefinitionService definitionService,
			MigrationSourceService sourceService,
			MigrationRunService runService,
			SourceInspectionService sourceInspectionService,
			MigrationBatchWorker batchWorker,
			ReconciliationService reconciliationService,
			MigrationDefinitionRepository definitionRepository,
			TransactionTemplate transactionTemplate,
			ArtifactChecksumCalculator checksumCalculator,
			MigrationBatchRepository batchRepository,
			@Qualifier(PostgreSqlTargetClientConfig.DATASOURCE_BEAN) DataSource targetDataSource,
			DataSource controlDataSource) {
		this.mappingService = mappingService;
		this.definitionService = definitionService;
		this.sourceService = sourceService;
		this.runService = runService;
		this.sourceInspectionService = sourceInspectionService;
		this.batchWorker = batchWorker;
		this.reconciliationService = reconciliationService;
		this.definitionRepository = definitionRepository;
		this.transactionTemplate = transactionTemplate;
		this.checksumCalculator = checksumCalculator;
		this.batchRepository = batchRepository;
		this.targetDataSource = targetDataSource;
		this.controlDataSource = controlDataSource;
	}

	void ensureStaging() throws Exception {
		try (Connection connection = targetDataSource.getConnection()) {
			connection.createStatement().execute("CREATE SCHEMA IF NOT EXISTS migration_staging");
			connection.createStatement().execute("""
					CREATE TABLE IF NOT EXISTS migration_staging.address_load (
					  migration_run_id UUID NOT NULL,
					  migration_batch_id BIGINT NOT NULL,
					  source_record_number BIGINT NOT NULL,
					  target_payload JSONB NOT NULL,
					  idempotency_key VARCHAR(500),
					  loaded_at TIMESTAMPTZ NOT NULL,
					  UNIQUE (migration_run_id, migration_batch_id, source_record_number)
					)
					""");
		}
	}

	BenchmarkResult runEndToEnd(BenchmarkConfiguration configuration, String objectKey, int workers) throws Exception {
		ensureStaging();
		UUID runId = createPreparedRun(configuration, objectKey);
		Instant started = Instant.now();
		long heapBefore = BenchmarkMetrics.heapUsedBytes();
		AtomicLong peakHeap = new AtomicLong(heapBefore);
		AtomicInteger batches = new AtomicInteger();
		processAll(runId, workers, peakHeap, batches);
		long processMs = java.time.Duration.between(started, Instant.now()).toMillis();
		Instant reconStart = Instant.now();
		MigrationReconciliationResponse recon = reconciliationService.reconcile(runId);
		long reconMs = java.time.Duration.between(reconStart, Instant.now()).toMillis();
		Instant ended = Instant.now();
		MigrationRunResponse run = runService.getById(runId);
		long checksumOne = timeChecksum(objectKey);
		long estimatedChecksum = checksumOne * Math.max(1, batches.get());
		Object targetCount = recon.reconciliation().target() == null
				? null
				: recon.reconciliation().target().details().get("targetCount");
		Map<String, Object> extras = new LinkedHashMap<>();
		extras.put("heapBeforeBytes", heapBefore);
		extras.put("checksumOnceMs", checksumOne);
		extras.put("checksumEstimatedTotalMs", estimatedChecksum);
		extras.put("checksumPctOfProcess", processMs == 0 ? 0 : estimatedChecksum * 100.0 / processMs);
		extras.put("reconciliationStatus", recon.status().name());
		extras.put("runStatus", run.status().name());
		extras.put("hikari", hikariSnapshot());
		extras.put("internallyConsistent", recon.reconciliation().count().details().get("internallyConsistent"));
		return new BenchmarkResult(
				"e2e",
				configuration.recordCount(),
				configuration.batchSize(),
				workers,
				batches.get(),
				processMs,
				BenchmarkMetrics.recordsPerSecond(configuration.recordCount(), processMs),
				nz(run.successRecords()),
				nz(run.validationFailures()) + nz(run.loadFailures()) + nz(run.rejectedRecords()),
				targetCount instanceof Number number ? number.longValue() : null,
				reconMs,
				estimatedChecksum,
				0L,
				peakHeap.get(),
				BenchmarkMetrics.heapUsedBytes(),
				recon.status().name(),
				started,
				ended,
				extras);
	}

	UUID createPreparedRun(BenchmarkConfiguration configuration, String objectKey) {
		UUID mappingId = mappingService.create(new CreateMigrationMappingRequest(
				"bench-" + System.nanoTime(), "1.0.0", "FILE", "POSTGRESQL", "benchmark", Map.of())).id();
		mappingService.createRule(mappingId, rule(1, "street_name", "streetName", TransformationType.TRIM, true, Map.of()));
		mappingService.createRule(mappingId, rule(2, "city", "city", TransformationType.DIRECT, false, Map.of()));
		mappingService.createRule(mappingId, rule(3, "state", "state", TransformationType.UPPERCASE, false, Map.of()));
		mappingService.createRule(mappingId, rule(4, "postal_code", "postcode", TransformationType.DIRECT, false, Map.of()));
		mappingService.createRule(mappingId, rule(5, "country", "country", TransformationType.LOWERCASE, false, Map.of()));
		mappingService.createRule(mappingId, rule(6, null, "addressLine1", TransformationType.CONCAT, false,
				Map.of("sourceFields", List.of("house_number", "street_name", "street_suffix"), "separator", " ")));
		mappingService.createRule(mappingId, rule(7, "address_line_2", "addressLine2", TransformationType.SPLIT, false,
				Map.of("delimiter", " ", "targetFields", List.of("addressLine2"))));
		mappingService.createRule(mappingId, rule(8, "street_name", "streetNormalized", TransformationType.REGEX, false,
				Map.of("pattern", " +", "replacement", " ")));
		mappingService.createRule(mappingId, rule(9, "state", "stateName", TransformationType.CONDITIONAL, false,
				Map.of("condition", Map.of("operator", "EQUALS", "sourceField", "state", "value", "TN"),
						"thenValue", "TENNESSEE", "elseValue", "OTHER")));
		mappingService.createRule(mappingId, rule(10, "opened_on", "openedOn", TransformationType.DATE_FORMAT, false,
				Map.of("sourceFormat", "yyyy-MM-dd", "targetFormat", "MM/dd/yyyy")));
		UUID definitionId = definitionService.create(new CreateMigrationDefinitionRequest(
				"bench-def-" + System.nanoTime(),
				"1.0.0",
				"benchmark",
				"FILE",
				"POSTGRESQL",
				"address",
				mappingId,
				configuration.batchSize(),
				LoadMode.BULK,
				Map.of("target", Map.of("schema", "migration_staging", "stagingTable", "address_load", "mergeStrategy", "NONE")))).id();
		transactionTemplate.executeWithoutResult(tx -> {
			MigrationDefinition definition = definitionRepository.findById(definitionId).orElseThrow();
			definition.setStatus(MigrationConfigStatus.ACTIVE);
		});
		sourceService.create(definitionId, new CreateMigrationSourceRequest(
				"csv-source",
				"FILE",
				Map.of("provider", "FILE_SYSTEM", "objectKey", objectKey, "fileFormat", "CSV", "hasHeader", true),
				true));
		UUID runId = runService.create(definitionId, new CreateMigrationRunRequest(null, null, null)).id();
		sourceInspectionService.prepare(runId);
		return runId;
	}

	PrepareMigrationRunResponse prepareExisting(UUID runId) {
		return sourceInspectionService.prepare(runId);
	}

	int processOne(UUID runId, AtomicLong peakHeap, AtomicInteger batches) {
		Optional<ProcessBatchResponse> processed = batchWorker.processNextBatch(runId);
		if (processed.isPresent()) {
			batches.incrementAndGet();
			peakHeap.accumulateAndGet(BenchmarkMetrics.heapUsedBytes(), Math::max);
		}
		return batches.get();
	}

	Optional<ProcessBatchResponse> processNextOptional(UUID runId) {
		return batchWorker.processNextBatch(runId);
	}

	MigrationReconciliationResponse reconcile(UUID runId) {
		return reconciliationService.reconcile(runId);
	}

	int processAll(UUID runId, int workers, AtomicLong peakHeap, AtomicInteger batches) throws Exception {
		if (workers <= 1) {
			while (true) {
				Optional<ProcessBatchResponse> processed = batchWorker.processNextBatch(runId);
				if (processed.isEmpty()) {
					break;
				}
				batches.incrementAndGet();
				peakHeap.accumulateAndGet(BenchmarkMetrics.heapUsedBytes(), Math::max);
			}
			return batches.get();
		}
		ExecutorService pool = Executors.newFixedThreadPool(workers);
		try {
			List<Future<?>> futures = new java.util.ArrayList<>();
			for (int i = 0; i < workers; i++) {
				futures.add(pool.submit(() -> {
					while (true) {
						Optional<ProcessBatchResponse> processed = batchWorker.processNextBatch(runId);
						if (processed.isEmpty()) {
							return;
						}
						batches.incrementAndGet();
						peakHeap.accumulateAndGet(BenchmarkMetrics.heapUsedBytes(), Math::max);
					}
				}));
			}
			for (Future<?> future : futures) {
				future.get();
			}
		} finally {
			pool.shutdownNow();
		}
		return batches.get();
	}

	MigrationRunResponse retry(UUID runId) {
		return runService.retry(runId);
	}

	long countStaging(UUID runId) throws Exception {
		try (Connection connection = targetDataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"SELECT COUNT(*) FROM migration_staging.address_load WHERE migration_run_id = ?")) {
			statement.setObject(1, runId);
			try (ResultSet resultSet = statement.executeQuery()) {
				resultSet.next();
				return resultSet.getLong(1);
			}
		}
	}

	int expireRemainingPendingLeases(UUID runId) {
		Integer expired = transactionTemplate.execute(status -> {
			List<MigrationBatch> pending = batchRepository.findByMigrationRun_IdAndStatusIn(
					runId, EnumSet.of(MigrationBatchStatus.PENDING));
			Instant leaseExpired = Instant.now().minusSeconds(60);
			for (MigrationBatch batch : pending) {
				batch.setStatus(MigrationBatchStatus.PROCESSING);
				batch.setWorkerId("crashed-worker");
				batch.setClaimedAt(leaseExpired);
				batch.setLeaseUntil(leaseExpired);
			}
			return pending.size();
		});
		return expired == null ? 0 : expired;
	}

	long timeChecksum(String objectKey) {
		long start = System.nanoTime();
		checksumCalculator.sha256(StorageProvider.FILE_SYSTEM, objectKey);
		return (System.nanoTime() - start) / 1_000_000L;
	}

	Map<String, Object> hikariSnapshot() {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("control", describe(controlDataSource));
		snapshot.put("target", describe(targetDataSource));
		return snapshot;
	}

	private static Map<String, Object> describe(DataSource dataSource) {
		if (!(dataSource instanceof HikariDataSource hikari)) {
			return Map.of("type", dataSource.getClass().getSimpleName());
		}
		HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
		return Map.of(
				"active", pool.getActiveConnections(),
				"idle", pool.getIdleConnections(),
				"total", pool.getTotalConnections(),
				"awaiting", pool.getThreadsAwaitingConnection(),
				"maxPoolSize", hikari.getMaximumPoolSize());
	}

	private static CreateMappingRuleRequest rule(
			int sequence,
			String source,
			String target,
			TransformationType type,
			boolean mandatory,
			Map<String, Object> configuration) {
		return new CreateMappingRuleRequest(sequence, source, target, type, null, null, configuration, mandatory, true);
	}

	private static long nz(Long value) {
		return value == null ? 0L : value;
	}
}
