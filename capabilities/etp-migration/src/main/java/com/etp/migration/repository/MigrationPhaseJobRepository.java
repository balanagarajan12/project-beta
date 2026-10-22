package com.etp.migration.repository;

import com.etp.migration.entity.MigrationPhaseJob;
import com.etp.migration.run.MigrationPhase;
import com.etp.migration.run.PhaseJobStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MigrationPhaseJobRepository extends JpaRepository<MigrationPhaseJob, UUID> {

	@Query("""
			select job from MigrationPhaseJob job
			where job.migrationRunId = :runId
			  and job.phase = :phase
			  and job.status in (:statuses)
			  and job.scope is null
			order by job.createdAt desc
			""")
	List<MigrationPhaseJob> findActiveWithoutScope(
			@Param("runId") UUID runId,
			@Param("phase") MigrationPhase phase,
			@Param("statuses") Collection<PhaseJobStatus> statuses);

	@Query("""
			select job from MigrationPhaseJob job
			where job.migrationRunId = :runId
			  and job.phase = :phase
			  and job.status in (:statuses)
			  and job.scope = :scope
			order by job.createdAt desc
			""")
	List<MigrationPhaseJob> findActiveWithScope(
			@Param("runId") UUID runId,
			@Param("phase") MigrationPhase phase,
			@Param("statuses") Collection<PhaseJobStatus> statuses,
			@Param("scope") String scope);

	Optional<MigrationPhaseJob> findFirstByMigrationRunIdAndPhaseOrderByCreatedAtDesc(
			UUID migrationRunId,
			MigrationPhase phase);

	Optional<MigrationPhaseJob> findFirstByMigrationRunIdAndPhaseInOrderByCreatedAtDesc(
			UUID migrationRunId,
			Collection<MigrationPhase> phases);
}
