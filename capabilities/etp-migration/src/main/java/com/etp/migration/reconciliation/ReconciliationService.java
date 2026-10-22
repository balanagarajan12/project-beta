package com.etp.migration.reconciliation;

import com.etp.migration.connector.target.MigrationTargetReconciliationProviderRegistry;
import com.etp.migration.connector.target.TargetInfrastructureException;
import com.etp.migration.connector.target.TargetVerification;
import com.etp.migration.connector.target.postgresql.PostgreSqlBusinessTargetVerifier;
import com.etp.migration.entity.MigrationBatch;
import com.etp.migration.entity.MigrationBatchStatus;
import com.etp.migration.entity.MigrationError;
import com.etp.migration.entity.MigrationMapping;
import com.etp.migration.entity.MigrationReconciliation;
import com.etp.migration.entity.MigrationRun;
import com.etp.migration.entity.ReconciliationStatus;
import com.etp.migration.entity.ReconciliationType;
import com.etp.migration.execution.ExecutionMode;
import com.etp.migration.mapping.EntityRelationshipConfiguration;
import com.etp.migration.mapping.EntityRelationshipMapping;
import com.etp.migration.mapping.RelationshipDefinition;
import com.etp.migration.reconciliation.dto.MigrationReconciliationResponse;
import com.etp.migration.reconciliation.dto.ReconciliationBundleResponse;
import com.etp.migration.reconciliation.dto.ReconciliationItemResponse;
import com.etp.migration.entity.MigrationPhaseJob;
import com.etp.migration.repository.MigrationBatchRepository;
import com.etp.migration.repository.MigrationPhaseJobRepository;
import com.etp.migration.run.MigrationPhase;
import com.etp.migration.repository.MigrationErrorRepository;
import com.etp.migration.repository.MigrationReconciliationRepository;
import com.etp.migration.repository.MigrationRunRepository;
import com.etp.migration.processing.MigrationErrorCodes;
import com.etp.migration.processing.MigrationErrorDetails;
import com.etp.migration.promotion.PromotionException;
import com.etp.migration.run.MigrationRunNotFoundException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ReconciliationService {

	private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
	private static final EnumSet<MigrationBatchStatus> ACTIVE_BATCHES = EnumSet.of(
			MigrationBatchStatus.PENDING,
			MigrationBatchStatus.CLAIMED,
			MigrationBatchStatus.PROCESSING,
			MigrationBatchStatus.RETRY_PENDING);

	private final TransactionTemplate transactionTemplate;
	private final MigrationRunRepository migrationRunRepository;
	private final MigrationBatchRepository migrationBatchRepository;
	private final MigrationReconciliationRepository reconciliationRepository;
	private final MigrationErrorRepository migrationErrorRepository;
	private final ReconciliationEngine reconciliationEngine;
	private final MigrationTargetReconciliationProviderRegistry targetReconciliationRegistry;
	private final PostgreSqlBusinessTargetVerifier businessTargetVerifier;
	private final MigrationPhaseJobRepository phaseJobRepository;

	@PersistenceContext
	private EntityManager entityManager;

	public ReconciliationService(
			PlatformTransactionManager transactionManager,
			MigrationRunRepository migrationRunRepository,
			MigrationBatchRepository migrationBatchRepository,
			MigrationReconciliationRepository reconciliationRepository,
			MigrationErrorRepository migrationErrorRepository,
			ReconciliationEngine reconciliationEngine,
			MigrationTargetReconciliationProviderRegistry targetReconciliationRegistry,
			PostgreSqlBusinessTargetVerifier businessTargetVerifier,
			MigrationPhaseJobRepository phaseJobRepository) {
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.migrationRunRepository = migrationRunRepository;
		this.migrationBatchRepository = migrationBatchRepository;
		this.reconciliationRepository = reconciliationRepository;
		this.migrationErrorRepository = migrationErrorRepository;
		this.reconciliationEngine = reconciliationEngine;
		this.targetReconciliationRegistry = targetReconciliationRegistry;
		this.businessTargetVerifier = businessTargetVerifier;
		this.phaseJobRepository = phaseJobRepository;
	}

	public MigrationReconciliationResponse reconcile(UUID runId) {
		return reconcile(runId, null);
	}

	public MigrationReconciliationResponse reconcile(UUID runId, String verificationScope) {
		return reconcile(runId, verificationScope, () -> { });
	}

	public void assertSubmittable(UUID runId, String verificationScope) {
		transactionTemplate.executeWithoutResult(status -> {
			MigrationRun run = migrationRunRepository.findById(runId)
					.orElseThrow(() -> new MigrationRunNotFoundException(runId));
			long active = migrationBatchRepository.countByMigrationRun_IdAndStatusIn(runId, ACTIVE_BATCHES);
			if (active > 0) {
				throw new ReconciliationNotReadyException("Migration run still has active batches");
			}
			if (isBusinessTarget(verificationScope)) {
				if (run.getMigrationDefinition().getTargetType() == null
						|| !"POSTGRESQL".equalsIgnoreCase(run.getMigrationDefinition().getTargetType())) {
					throw new PromotionException(
							HttpStatus.BAD_REQUEST.value(),
							MigrationErrorCodes.PROMOTION_UNSUPPORTED_TARGET,
							"Business-target verification is only supported for PostgreSQL targets");
				}
				Map<String, Object> configuration = run.getMigrationDefinition().getConfiguration();
				businessTargetVerifier.assertExecutable(runId, configuration == null ? Map.of() : configuration);
			}
		});
	}

	public MigrationReconciliationResponse reconcile(UUID runId, String verificationScope, Runnable progress) {
		Runnable report = progress == null ? () -> { } : progress;
		MigrationReconciliationResponse[] response = new MigrationReconciliationResponse[1];
		com.etp.migration.processing.PipelineTiming.open(() -> {
			long started = com.etp.migration.processing.PipelineTiming.start();
			try {
				response[0] = reconcileMeasured(runId, verificationScope, report);
			} finally {
				com.etp.migration.processing.PipelineTiming.stop(
						com.etp.migration.processing.PipelineTiming.Stage.RECONCILIATION,
						started);
			}
		});
		return response[0];
	}

	private MigrationReconciliationResponse reconcileMeasured(
			UUID runId,
			String verificationScope,
			Runnable progress) {
		boolean businessTarget = isBusinessTarget(verificationScope);
		progress.run();
		PreparedRun prepared = transactionTemplate.execute(status -> prepare(runId));
		progress.run();
		TargetVerification verification = businessTarget
				? verifyBusinessTarget(prepared)
				: verifyTarget(prepared);
		progress.run();
		if (prepared.entityReconciliation() != null) {
			applyTargetFacts(prepared, verification);
		}
		ReconciliationContext context = toContext(prepared.run(), verification);
		ReconciliationComputation count = reconciliationEngine.count(context);
		ReconciliationComputation checksum = reconciliationEngine.checksum(context);
		ReconciliationComputation target = reconciliationEngine.target(context);
		ReconciliationComputation full = reconciliationEngine.full(context, count, checksum, target);
		List<ReconciliationComputation> computations = List.of(count, checksum, target, full);
		if (prepared.entityReconciliation() != null) {
			computations = prepared.entityReconciliation().finish().apply(computations);
		}
		List<ReconciliationComputation> persisted = computations;
		return transactionTemplate.execute(status -> persist(runId, persisted));
	}

	public MigrationReconciliationResponse get(UUID runId) {
		return transactionTemplate.execute(status -> {
			if (!migrationRunRepository.existsById(runId)) {
				throw new MigrationRunNotFoundException(runId);
			}
			List<MigrationReconciliation> rows = reconciliationRepository.findByMigrationRun_Id(runId);
			MigrationPhaseJob job = phaseJobRepository
					.findFirstByMigrationRunIdAndPhaseOrderByCreatedAtDesc(runId, MigrationPhase.RECONCILIATION)
					.orElse(null);
			if (rows.isEmpty() && job == null) {
				throw new ReconciliationNotFoundException(runId);
			}
			MigrationReconciliationResponse response = rows.isEmpty()
					? new MigrationReconciliationResponse(runId, null, new ReconciliationBundleResponse(null, null, null, null))
					: toResponse(runId, rows);
			if (job == null) {
				return response;
			}
			return response.withJob(job.getId(), job.getStatus().name());
		});
	}

	private PreparedRun prepare(UUID runId) {
		MigrationRun run = migrationRunRepository.findById(runId)
				.orElseThrow(() -> new MigrationRunNotFoundException(runId));
		run.getMigrationDefinition().getTargetType();
		run.getMigrationDefinition().getConfiguration();
		run.getSourceSnapshot();
		long active = migrationBatchRepository.countByMigrationRun_IdAndStatusIn(runId, ACTIVE_BATCHES);
		if (active > 0) {
			throw new ReconciliationNotReadyException("Migration run still has active batches");
		}
		Map<String, Object> mappingConfiguration = mappingConfiguration(run);
		EntityReconciliationAccumulator entityReconciliation = null;
		if (ExecutionMode.entityRelationship(mappingConfiguration)) {
			entityReconciliation = observeEntityRun(runId, mappingConfiguration);
		}
		return new PreparedRun(
				run,
				run.getMigrationDefinition().getTargetType(),
				run.getMigrationDefinition().getConfiguration() == null
						? Map.of()
						: new HashMap<>(run.getMigrationDefinition().getConfiguration()),
				entityReconciliation);
	}

	private TargetVerification verifyBusinessTarget(PreparedRun prepared) {
		if (prepared.targetType() == null || !"POSTGRESQL".equalsIgnoreCase(prepared.targetType())) {
			throw new PromotionException(
					HttpStatus.BAD_REQUEST.value(),
					MigrationErrorCodes.PROMOTION_UNSUPPORTED_TARGET,
					"Business-target verification is only supported for PostgreSQL targets");
		}
		return businessTargetVerifier.verify(prepared.run().getId(), prepared.configuration());
	}

	private TargetVerification verifyTarget(PreparedRun prepared) {
		try {
			return targetReconciliationRegistry.find(prepared.targetType())
					.map(provider -> provider.verify(prepared.run().getId(), prepared.configuration()))
					.orElseGet(() -> TargetVerification.unavailable(
							"BUSINESS_TARGET",
							"Target count verification is not available for target type " + prepared.targetType()));
		} catch (TargetInfrastructureException ex) {
			log.warn("Target verification failed runId={} targetType={}", prepared.run().getId(), prepared.targetType());
			return TargetVerification.unavailable("STAGING", "Target verification failed");
		}
	}

	private MigrationReconciliationResponse persist(UUID runId, List<ReconciliationComputation> computations) {
		MigrationRun run = migrationRunRepository.findById(runId).orElseThrow(() -> new MigrationRunNotFoundException(runId));
		Instant now = Instant.now();
		for (ReconciliationComputation computation : computations) {
			MigrationReconciliation entity = reconciliationRepository
					.findByMigrationRun_IdAndReconciliationType(runId, computation.type())
					.orElseGet(MigrationReconciliation::new);
			entity.setMigrationRun(run);
			entity.setReconciliationType(computation.type());
			entity.setSourceCount(computation.sourceCount());
			entity.setProcessedCount(computation.processedCount());
			entity.setSuccessCount(computation.successCount());
			entity.setRejectedCount(computation.rejectedCount());
			entity.setDuplicateCount(computation.duplicateCount());
			entity.setFailureCount(computation.failureCount());
			entity.setDifferenceCount(computation.differenceCount());
			entity.setStatus(computation.status());
			entity.setDetails(new HashMap<>(computation.details()));
			if (entity.getStartedAt() == null) {
				entity.setStartedAt(now);
			}
			entity.setCompletedAt(now);
			reconciliationRepository.save(entity);
		}
		return toResponse(runId, reconciliationRepository.findByMigrationRun_Id(runId));
	}

	private static ReconciliationContext toContext(MigrationRun run, TargetVerification verification) {
		long sourceCount = sourceCount(run);
		long failureCount = nz(run.getValidationFailures()) + nz(run.getEnrichmentFailures()) + nz(run.getLoadFailures());
		ChecksumInfo checksum = checksum(run);
		return new ReconciliationContext(
				run,
				sourceCount,
				nz(run.getProcessedRecords()),
				nz(run.getSuccessRecords()),
				nz(run.getRejectedRecords()),
				nz(run.getDuplicateRecords()),
				failureCount,
				checksum.value(),
				checksum.algorithm(),
				verification);
	}

	@SuppressWarnings("unchecked")
	private static long sourceCount(MigrationRun run) {
		Map<String, Object> snapshot = run.getSourceSnapshot();
		if (snapshot != null) {
			Object direct = snapshot.get("sourceRecordCount");
			if (direct instanceof Number number) {
				return number.longValue();
			}
			Object source = snapshot.get("source");
			if (source instanceof Map<?, ?> map && map.get("recordCount") instanceof Number number) {
				return number.longValue();
			}
		}
		return nz(run.getTotalRecords());
	}

	@SuppressWarnings("unchecked")
	private static ChecksumInfo checksum(MigrationRun run) {
		Map<String, Object> snapshot = run.getSourceSnapshot();
		if (snapshot == null) {
			return new ChecksumInfo(null, null);
		}
		Object source = snapshot.get("source");
		if (source instanceof Map<?, ?> map) {
			Object checksum = map.get("checksum");
			if (checksum instanceof Map<?, ?> checksumMap) {
				Object value = checksumMap.get("value");
				Object algorithm = checksumMap.get("algorithm");
				return new ChecksumInfo(
						value == null ? null : value.toString(),
						algorithm == null ? null : algorithm.toString());
			}
		}
		return new ChecksumInfo(null, null);
	}

	private static MigrationReconciliationResponse toResponse(UUID runId, List<MigrationReconciliation> rows) {
		ReconciliationItemResponse count = null;
		ReconciliationItemResponse checksum = null;
		ReconciliationItemResponse target = null;
		ReconciliationItemResponse full = null;
		for (MigrationReconciliation row : rows) {
			ReconciliationItemResponse item = toItem(row);
			switch (row.getReconciliationType()) {
				case COUNT -> count = item;
				case CHECKSUM -> checksum = item;
				case TARGET -> target = item;
				case FULL -> full = item;
			}
		}
		ReconciliationStatus status = full == null ? ReconciliationStatus.PENDING : full.status();
		return new MigrationReconciliationResponse(
				runId,
				status,
				new ReconciliationBundleResponse(count, checksum, target, full));
	}

	static ReconciliationItemResponse toItem(MigrationReconciliation row) {
		return new ReconciliationItemResponse(
				row.getId(),
				row.getReconciliationType(),
				row.getStatus(),
				row.getSourceCount(),
				row.getProcessedCount(),
				row.getSuccessCount(),
				row.getRejectedCount(),
				row.getDuplicateCount(),
				row.getFailureCount(),
				row.getDifferenceCount(),
				MigrationErrorDetails.publicReconciliationDetails(row.getDetails()),
				row.getStartedAt(),
				row.getCompletedAt());
	}

	private static boolean isBusinessTarget(String verificationScope) {
		return verificationScope != null && "BUSINESS_TARGET".equalsIgnoreCase(verificationScope.trim());
	}

	private EntityReconciliationAccumulator observeEntityRun(UUID runId, Map<String, Object> mappingConfiguration) {
		EntityReconciliationAccumulator accumulator = new EntityReconciliationAccumulator();
		EntityRelationshipMapping mapping = EntityRelationshipConfiguration.parse(mappingConfiguration);
		for (RelationshipDefinition relationship : mapping.relationships()) {
			if (relationship.cardinality() != null) {
				accumulator.noteCardinality(relationship.parent(), relationship.child(), relationship.cardinality().name());
			}
		}
		int page = 0;
		Page<MigrationBatch> batchPage;
		do {
			batchPage = migrationBatchRepository.findByMigrationRun_Id(
					runId,
					PageRequest.of(page, 20, Sort.by("batchNumber")));
			for (MigrationBatch batch : batchPage.getContent()) {
				accumulator.beginBatch();
				EntityRunFacts.foldLocator(accumulator, batch.getSourceLocator());
				entityManager.detach(batch);
			}
			page++;
		} while (batchPage.hasNext());
		try (Stream<MigrationError> errors = migrationErrorRepository.streamByMigrationRunId(runId)) {
			errors.forEach(error -> {
				EntityRunFacts.foldError(
						accumulator,
						error.getErrorCode(),
						error.getErrorType(),
						error.getSourceRecordKey(),
						error.getErrorDetails());
				entityManager.detach(error);
			});
		}
		return accumulator;
	}

	private void applyTargetFacts(PreparedRun prepared, TargetVerification verification) {
		EntityReconciliationAccumulator accumulator = prepared.entityReconciliation();
		if (verification.available() && verification.targetCount() != null) {
			accumulator.targetCount(verification.targetCount());
		} else {
			accumulator.targetCountUnavailable();
		}
		var provider = targetReconciliationRegistry.find(prepared.targetType());
		if (provider.isEmpty() || !provider.get().entityProjectionAvailable()) {
			accumulator.markTargetResultsUnavailable();
			return;
		}
		try {
			accumulator.markTargetResultsKnown();
			provider.get().streamStagedEntities(
					prepared.run().getId(),
					prepared.configuration(),
					view -> EntityRunFacts.foldStaged(accumulator, view));
		} catch (TargetInfrastructureException ex) {
			log.warn("Entity target projection failed runId={}", prepared.run().getId());
			accumulator.markTargetResultsUnavailable();
		}
	}

	private static Map<String, Object> mappingConfiguration(MigrationRun run) {
		MigrationMapping mapping = run.getMigrationDefinition().getMapping();
		if (mapping == null || mapping.getConfiguration() == null) {
			return Map.of();
		}
		return mapping.getConfiguration();
	}

	private static long nz(Long value) {
		return value == null ? 0L : value;
	}

	private record PreparedRun(
			MigrationRun run,
			String targetType,
			Map<String, Object> configuration,
			EntityReconciliationAccumulator entityReconciliation) {
	}

	private record ChecksumInfo(String value, String algorithm) {
	}
}
