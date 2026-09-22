package com.etp.migration.run;

import com.etp.migration.batch.BatchPlanner;
import com.etp.migration.batch.MigrationBatchQueueService;
import com.etp.migration.batch.MigrationBatchTransitions;
import com.etp.migration.entity.MigrationBatch;
import com.etp.migration.entity.MigrationBatchStatus;
import com.etp.migration.entity.MigrationConfigStatus;
import com.etp.migration.entity.MigrationDefinition;
import com.etp.migration.entity.MigrationRun;
import com.etp.migration.entity.MigrationRunStatus;
import com.etp.migration.migration.ControlPlaneSupport;
import com.etp.migration.migration.MigrationDefinitionNotFoundException;
import com.etp.migration.migration.dto.PagedResponse;
import com.etp.migration.batch.dto.MigrationBatchResponse;
import com.etp.migration.repository.MigrationBatchRepository;
import com.etp.migration.repository.MigrationDefinitionRepository;
import com.etp.migration.repository.MigrationRunRepository;
import com.etp.migration.run.dto.CreateMigrationRunRequest;
import com.etp.migration.run.dto.MigrationRunResponse;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MigrationRunService {

	private final MigrationDefinitionRepository migrationDefinitionRepository;
	private final MigrationRunRepository migrationRunRepository;
	private final MigrationBatchRepository migrationBatchRepository;
	private final BatchPlanner batchPlanner;

	public MigrationRunService(
			MigrationDefinitionRepository migrationDefinitionRepository,
			MigrationRunRepository migrationRunRepository,
			MigrationBatchRepository migrationBatchRepository,
			BatchPlanner batchPlanner) {
		this.migrationDefinitionRepository = migrationDefinitionRepository;
		this.migrationRunRepository = migrationRunRepository;
		this.migrationBatchRepository = migrationBatchRepository;
		this.batchPlanner = batchPlanner;
	}

	@Transactional
	public MigrationRunResponse create(UUID migrationId, CreateMigrationRunRequest request) {
		MigrationDefinition definition = migrationDefinitionRepository.findByIdForUpdate(migrationId)
				.orElseThrow(() -> new MigrationDefinitionNotFoundException(migrationId));
		if (definition.getStatus() != MigrationConfigStatus.ACTIVE) {
			throw new DefinitionNotActiveException(migrationId, definition.getStatus());
		}

		String idempotencyKey = normalizeIdempotencyKey(request == null ? null : request.idempotencyKey());
		if (idempotencyKey != null) {
			var existing = migrationRunRepository.findByMigrationDefinition_IdAndIdempotencyKey(
					migrationId, idempotencyKey);
			if (existing.isPresent()) {
				return toRunResponse(existing.get());
			}
		}

		Instant now = Instant.now();
		long nextNumber = migrationRunRepository.findMaxRunNumber(migrationId) + 1;
		MigrationRun run = new MigrationRun();
		run.setId(UUID.randomUUID());
		run.setMigrationDefinition(definition);
		run.setRunNumber(nextNumber);
		run.setIdempotencyKey(idempotencyKey);
		run.setStatus(MigrationRunStatus.CREATED);
		run.setRequestedBy(ControlPlaneSupport.SYSTEM_ACTOR);
		run.setSourceSnapshot(ControlPlaneSupport.copyConfiguration(
				request == null ? null : request.sourceSnapshot()));
		run.setConfigurationSnapshot(ControlPlaneSupport.copyConfiguration(
				request == null ? null : request.configurationSnapshot()));
		run.setCreatedAt(now);
		run.setUpdatedAt(now);
		MigrationRunTransitions.require(MigrationRunStatus.CREATED, MigrationRunStatus.QUEUED);
		run.setStatus(MigrationRunStatus.QUEUED);

		try {
			return toRunResponse(migrationRunRepository.saveAndFlush(run));
		} catch (DataIntegrityViolationException ex) {
			if (idempotencyKey != null
					&& ControlPlaneSupport.isUniqueViolation(ex, "uk_migration_run_idempotency")) {
				return migrationRunRepository
						.findByMigrationDefinition_IdAndIdempotencyKey(migrationId, idempotencyKey)
						.map(this::toRunResponse)
						.orElseThrow(() -> ex);
			}
			throw ex;
		}
	}

	@Transactional(readOnly = true)
	public PagedResponse<MigrationRunResponse> list(UUID migrationId, int page, int size) {
		if (!migrationDefinitionRepository.existsById(migrationId)) {
			throw new MigrationDefinitionNotFoundException(migrationId);
		}
		Page<MigrationRun> result = migrationRunRepository.findByMigrationDefinition_Id(
				migrationId,
				ControlPlaneSupport.pageRequest(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
		return new PagedResponse<>(
				result.getContent().stream().map(this::toRunResponse).toList(),
				result.getNumber(),
				result.getSize(),
				result.getTotalElements(),
				result.getTotalPages());
	}

	@Transactional(readOnly = true)
	public MigrationRunResponse getById(UUID runId) {
		return toRunResponse(requireRun(runId));
	}

	@Transactional
	public MigrationRunResponse cancel(UUID runId) {
		MigrationRun run = requireRun(runId);
		MigrationRunTransitions.require(run.getStatus(), MigrationRunStatus.CANCEL_REQUESTED);
		Instant now = Instant.now();
		run.setStatus(MigrationRunStatus.CANCEL_REQUESTED);
		run.setUpdatedAt(now);
		List<MigrationBatch> cancellable = migrationBatchRepository.findByMigrationRun_IdAndStatusIn(
				runId,
				EnumSet.of(MigrationBatchStatus.PENDING, MigrationBatchStatus.RETRY_PENDING));
		for (MigrationBatch batch : cancellable) {
			MigrationBatchTransitions.require(batch.getStatus(), MigrationBatchStatus.CANCELLED);
			batch.setStatus(MigrationBatchStatus.CANCELLED);
			batch.setUpdatedAt(now);
		}
		long inFlight = migrationBatchRepository.countByMigrationRun_IdAndStatusIn(
				runId,
				EnumSet.of(MigrationBatchStatus.CLAIMED, MigrationBatchStatus.PROCESSING));
		if (inFlight == 0) {
			MigrationRunTransitions.require(MigrationRunStatus.CANCEL_REQUESTED, MigrationRunStatus.CANCELLED);
			run.setStatus(MigrationRunStatus.CANCELLED);
			run.setCompletedAt(now);
		}
		return toRunResponse(run);
	}

	@Transactional
	public MigrationRunResponse retry(UUID runId) {
		MigrationRun run = requireRun(runId);
		if (run.getStatus() != MigrationRunStatus.FAILED
				&& run.getStatus() != MigrationRunStatus.COMPLETED_WITH_ERRORS) {
			throw new InvalidStateTransitionException(
					"Cannot retry migration run in status " + run.getStatus());
		}
		List<MigrationBatch> retryable = migrationBatchRepository.findByMigrationRun_IdAndStatusIn(
				runId,
				EnumSet.of(MigrationBatchStatus.FAILED, MigrationBatchStatus.COMPLETED_WITH_ERRORS));
		if (retryable.isEmpty()) {
			throw new InvalidStateTransitionException("No retryable batches exist for migration run " + runId);
		}
		Instant now = Instant.now();
		for (MigrationBatch batch : retryable) {
			MigrationBatchQueueService.reverseRunContribution(run, batch, now);
			MigrationBatchTransitions.require(batch.getStatus(), MigrationBatchStatus.RETRY_PENDING);
			batch.setStatus(MigrationBatchStatus.RETRY_PENDING);
			batch.setWorkerId(null);
			batch.setClaimedAt(null);
			batch.setLeaseUntil(null);
			batch.setCompletedAt(null);
			batch.setErrorMessage(null);
			batch.setProcessedCount(0);
			batch.setSuccessCount(0);
			batch.setDuplicateCount(0);
			batch.setValidationFailureCount(0);
			batch.setEnrichmentFailureCount(0);
			batch.setLoadFailureCount(0);
			batch.setRejectedCount(0);
			batch.setUpdatedAt(now);
		}
		run.setCompletedAt(null);
		MigrationRunTransitions.require(run.getStatus(), MigrationRunStatus.QUEUED);
		run.setStatus(MigrationRunStatus.QUEUED);
		run.setUpdatedAt(now);
		return toRunResponse(run);
	}

	@Transactional
	public PagedResponse<MigrationBatchResponse> planBatches(UUID runId, long recordCount) {
		MigrationRun run = requireRun(runId);
		if (run.getStatus() != MigrationRunStatus.QUEUED && run.getStatus() != MigrationRunStatus.CREATED) {
			throw new InvalidStateTransitionException(
					"Cannot plan batches for migration run in status " + run.getStatus());
		}
		if (migrationBatchRepository.existsByMigrationRun_Id(runId)) {
			throw new InvalidStateTransitionException("Batches already exist for migration run " + runId);
		}
		int batchSize = run.getMigrationDefinition().getBatchSize() == null
				? 10_000
				: run.getMigrationDefinition().getBatchSize();
		List<BatchPlanner.PlannedBatch> planned = batchPlanner.plan(
				run, new BatchPlanner.BatchPlanRequest(recordCount, batchSize));
		Instant now = Instant.now();
		for (BatchPlanner.PlannedBatch item : planned) {
			MigrationBatch batch = new MigrationBatch();
			batch.setMigrationRun(run);
			batch.setBatchNumber(item.batchNumber());
			batch.setStatus(MigrationBatchStatus.PENDING);
			batch.setRecordCount(item.recordCount());
			batch.setSourceLocator(item.sourceLocator());
			batch.setCreatedAt(now);
			batch.setUpdatedAt(now);
			migrationBatchRepository.save(batch);
		}
		run.setTotalRecords(recordCount);
		run.setTotalBatches(planned.size());
		run.setUpdatedAt(now);
		if (run.getStatus() == MigrationRunStatus.CREATED) {
			MigrationRunTransitions.require(MigrationRunStatus.CREATED, MigrationRunStatus.QUEUED);
			run.setStatus(MigrationRunStatus.QUEUED);
		}
		return listBatches(runId, 0, ControlPlaneSupport.MAX_PAGE_SIZE);
	}

	@Transactional(readOnly = true)
	public PagedResponse<MigrationBatchResponse> listBatches(UUID runId, int page, int size) {
		requireRun(runId);
		Page<MigrationBatch> result = migrationBatchRepository.findByMigrationRun_Id(
				runId,
				ControlPlaneSupport.pageRequest(page, size, Sort.by(Sort.Direction.ASC, "batchNumber")));
		return new PagedResponse<>(
				result.getContent().stream().map(this::toBatchResponse).toList(),
				result.getNumber(),
				result.getSize(),
				result.getTotalElements(),
				result.getTotalPages());
	}

	private MigrationRun requireRun(UUID runId) {
		return migrationRunRepository.findById(runId)
				.orElseThrow(() -> new MigrationRunNotFoundException(runId));
	}

	private String normalizeIdempotencyKey(String idempotencyKey) {
		if (idempotencyKey == null || idempotencyKey.isBlank()) {
			return null;
		}
		return idempotencyKey.trim();
	}

	private MigrationRunResponse toRunResponse(MigrationRun run) {
		return new MigrationRunResponse(
				run.getId(),
				run.getMigrationDefinition().getId(),
				run.getRunNumber(),
				run.getIdempotencyKey(),
				run.getStatus(),
				run.getStartedAt(),
				run.getCompletedAt(),
				run.getTotalRecords(),
				run.getProcessedRecords(),
				run.getSuccessRecords(),
				run.getDuplicateRecords(),
				run.getValidationFailures(),
				run.getEnrichmentFailures(),
				run.getLoadFailures(),
				run.getRejectedRecords(),
				run.getTotalBatches(),
				run.getCompletedBatches(),
				run.getFailedBatches(),
				run.getRequestedBy(),
				run.getCreatedAt(),
				run.getUpdatedAt());
	}

	private MigrationBatchResponse toBatchResponse(MigrationBatch batch) {
		return new MigrationBatchResponse(
				batch.getId(),
				batch.getMigrationRun().getId(),
				batch.getBatchNumber(),
				batch.getStatus(),
				batch.getRecordCount(),
				batch.getProcessedCount(),
				batch.getSuccessCount(),
				batch.getDuplicateCount(),
				batch.getValidationFailureCount(),
				batch.getEnrichmentFailureCount(),
				batch.getLoadFailureCount(),
				batch.getRejectedCount(),
				batch.getAttemptCount(),
				batch.getWorkerId(),
				batch.getClaimedAt(),
				batch.getLeaseUntil(),
				batch.getStartedAt(),
				batch.getCompletedAt(),
				batch.getErrorMessage());
	}
}
