package com.etp.migration.identity;

import com.etp.migration.entity.MigrationIdentityAllocation;
import com.etp.migration.mapping.TargetIdentitySpec;
import com.etp.migration.repository.MigrationIdentityAllocationRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assigns target ids for one entity chunk.
 * A source identity that already has a row for this run is returned unchanged and does not take a new number.
 * New {@code PREFIX_SEQUENCE} numbers come from one range reservation. {@code PREFIX_SOURCE_VALUE} does not use the allocator.
 */
@Service
public class GeneratedTargetIdentityService {

	private final MigrationIdentityAllocationRepository assignments;
	private final TargetIdentityAllocator allocator;
	private final TargetIdentityFloorProbe floorProbe;

	public GeneratedTargetIdentityService(
			MigrationIdentityAllocationRepository assignments,
			TargetIdentityAllocator allocator,
			TargetIdentityFloorProbe floorProbe) {
		this.assignments = assignments;
		this.allocator = allocator;
		this.floorProbe = floorProbe;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Map<String, String> assign(
			UUID migrationRunId,
			String entityName,
			List<String> sourceIdentities,
			TargetIdentitySpec spec,
			String schema,
			String table,
			String idColumn) {
		if (migrationRunId == null) {
			throw new IllegalArgumentException("migration run id is required");
		}
		if (spec == null) {
			return Map.of();
		}
		Map<String, String> assigned = new LinkedHashMap<>();
		if (sourceIdentities != null && !sourceIdentities.isEmpty()) {
			for (MigrationIdentityAllocation existing : assignments
					.findByMigrationRunIdAndEntityNameAndSourceIdentityIn(migrationRunId, entityName, sourceIdentities)) {
				assigned.put(existing.getSourceIdentity(), existing.getTargetIdentity());
			}
		}
		List<String> missing = new ArrayList<>();
		if (sourceIdentities != null) {
			for (String sourceIdentity : sourceIdentities) {
				if (sourceIdentity != null && !sourceIdentity.isBlank() && !assigned.containsKey(sourceIdentity)) {
					missing.add(sourceIdentity);
				}
			}
		}
		if (missing.isEmpty()) {
			return assigned;
		}
		if (TargetIdentitySpec.PREFIX_SOURCE_VALUE.equals(spec.strategy())) {
			for (String sourceIdentity : missing) {
				assigned.put(sourceIdentity, PrefixSequenceFormatter.prefixSourceValue(spec.prefix(), sourceIdentity));
			}
			return assigned;
		}
		if (!TargetIdentitySpec.PREFIX_SEQUENCE.equals(spec.strategy()) || spec.sequenceLength() == null) {
			throw new IllegalArgumentException("Unsupported target identity strategy");
		}
		long start = allocator.reserve(spec.prefix(), missing.size(), () -> floorProbe.existingMaximum(
				schema,
				table,
				idColumn,
				spec.prefix(),
				spec.sequenceLength()));
		long sequence = start;
		for (String sourceIdentity : missing) {
			String targetIdentity = PrefixSequenceFormatter.format(spec.prefix(), spec.sequenceLength(), sequence);
			MigrationIdentityAllocation row = new MigrationIdentityAllocation();
			row.setMigrationRunId(migrationRunId);
			row.setEntityName(entityName);
			row.setSourceIdentity(sourceIdentity);
			row.setTargetIdentity(targetIdentity);
			row.setSequenceNo(sequence);
			assignments.save(row);
			assigned.put(sourceIdentity, targetIdentity);
			sequence++;
		}
		assignments.flush();
		return assigned;
	}
}
