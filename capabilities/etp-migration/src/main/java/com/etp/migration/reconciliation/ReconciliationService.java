package com.etp.migration.reconciliation;

import com.etp.migration.connector.target.MigrationTargetReconciliationProviderRegistry;
import com.etp.migration.connector.target.TargetInfrastructureException;
import com.etp.migration.connector.target.TargetVerification;
import com.etp.migration.connector.target.postgresql.PostgreSqlBusinessTargetVerifier;
import com.etp.migration.entity.MigrationBatchStatus;
import com.etp.migration.entity.MigrationReconciliation;
import com.etp.migration.entity.MigrationRun;
import com.etp.migration.entity.ReconciliationStatus;
import com.etp.migration.entity.ReconciliationType;
import com.etp.migration.reconciliation.dto.MigrationReconciliationResponse;
import com.etp.migration.reconciliation.dto.ReconciliationBundleResponse;
import com.etp.migration.reconciliation.dto.ReconciliationItemResponse;
import com.etp.migration.repository.MigrationBatchRepository;
import com.etp.migration.repository.MigrationReconciliationRepository;
import com.etp.migration.repository.MigrationRunRepository;
import com.etp.migration.processing.MigrationErrorCodes;
import com.etp.migration.promotion.PromotionException;
import com.etp.migration.run.MigrationRunNotFoundException;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
	private final ReconciliationEngine reconciliationEngine;
	private final MigrationTargetReconciliationProviderRegistry targetReconciliationRegistry;
	private final PostgreSqlBusinessTargetVerifier businessTargetVerifier;

	public ReconciliationService(
			PlatformTransactionManager transactionManager,
			MigrationRunRepository migrationRunRepository,
			MigrationBatchRepository migrationBatchRepository,
			MigrationReconciliationRepository reconciliationRepository,
			ReconciliationEngine reconciliationEngine,
			MigrationTargetReconciliationProviderRegistry targetReconciliationRegistry,
			PostgreSqlBusinessTargetVerifier businessTargetVerifier) {
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.migrationRunRepository = migrationRunRepository;
		this.migrationBatchRepository = migrationBatchRepository;
		this.reconciliationRepository = reconciliationRepository;
		this.reconciliationEngine = reconciliationEngine;
		this.targetReconciliationRegistry = targetReconciliationRegistry;
		this.businessTargetVerifier = businessTargetVerifier;
	}

	public MigrationReconciliationResponse reconcile(UUID runId) {
		return reconcile(runId, null);
	}

	public MigrationReconciliationResponse reconcile(UUID runId, String verificationScope) {
		boolean businessTarget = isBusinessTarget(verificationScope);
		PreparedRun prepared = transactionTemplate.execute(status -> prepare(runId));
		TargetVerification verification = businessTarget
				? verifyBusinessTarget(prepared)
				: verifyTarget(prepared);
		ReconciliationContext context = toContext(prepared.run(), verification);
		ReconciliationComputation count = reconciliationEngine.count(context);
		ReconciliationComputation checksum = reconciliationEngine.checksum(context);
		ReconciliationComputation target = reconciliationEngine.target(context);
		ReconciliationComputation full = reconciliationEngine.full(context, count, checksum, target);
		return transactionTemplate.execute(status -> persist(runId, List.of(count, checksum, target, full)));
	}

	public MigrationReconciliationResponse get(UUID runId) {
		return transactionTemplate.execute(status -> {
			if (!migrationRunRepository.existsById(runId)) {
				throw new MigrationRunNotFoundException(runId);
			}
			List<MigrationReconciliation> rows = reconciliationRepository.findByMigrationRun_Id(runId);
			if (rows.isEmpty()) {
				throw new ReconciliationNotFoundException(runId);
			}
			return toResponse(runId, rows);
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
		return new PreparedRun(
				run,
				run.getMigrationDefinition().getTargetType(),
				run.getMigrationDefinition().getConfiguration() == null
						? Map.of()
						: new HashMap<>(run.getMigrationDefinition().getConfiguration()));
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

	private static ReconciliationItemResponse toItem(MigrationReconciliation row) {
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
				row.getDetails() == null ? Map.of() : new HashMap<>(row.getDetails()),
				row.getStartedAt(),
				row.getCompletedAt());
	}

	private static boolean isBusinessTarget(String verificationScope) {
		return verificationScope != null && "BUSINESS_TARGET".equalsIgnoreCase(verificationScope.trim());
	}

	private static long nz(Long value) {
		return value == null ? 0L : value;
	}

	private record PreparedRun(MigrationRun run, String targetType, Map<String, Object> configuration) {
	}

	private record ChecksumInfo(String value, String algorithm) {
	}
}
