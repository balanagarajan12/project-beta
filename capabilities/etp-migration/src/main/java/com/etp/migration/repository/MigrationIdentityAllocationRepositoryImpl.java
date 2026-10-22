package com.etp.migration.repository;

import com.etp.migration.entity.MigrationIdentityAllocation;
import com.etp.migration.processing.PipelineTiming;
import jakarta.persistence.EntityManager;
import java.sql.PreparedStatement;
import java.util.List;
import org.hibernate.Session;

/**
 * Inserts identity rows with JDBC batches. Hibernate does not batch
 * {@code GenerationType.IDENTITY} saves, and the generated key is not read again.
 */
public class MigrationIdentityAllocationRepositoryImpl implements MigrationIdentityAllocationBatch {

	static final int BATCH_SIZE = 500;

	private final EntityManager entityManager;

	public MigrationIdentityAllocationRepositoryImpl(EntityManager entityManager) {
		this.entityManager = entityManager;
	}

	@Override
	public void insertAll(List<MigrationIdentityAllocation> rows) {
		if (rows == null || rows.isEmpty()) {
			return;
		}
		entityManager.unwrap(Session.class).doWork(connection -> {
			String sql = """
					INSERT INTO migration_identity_allocation
					    (migration_run_id, entity_name, source_identity, target_identity, sequence_no)
					VALUES (?, ?, ?, ?, ?)
					""";
			try (PreparedStatement statement = connection.prepareStatement(sql)) {
				int pending = 0;
				for (MigrationIdentityAllocation row : rows) {
					statement.setObject(1, row.getMigrationRunId());
					statement.setString(2, row.getEntityName());
					statement.setString(3, row.getSourceIdentity());
					statement.setString(4, row.getTargetIdentity());
					statement.setLong(5, row.getSequenceNo());
					statement.addBatch();
					pending++;
					if (pending == BATCH_SIZE) {
						statement.executeBatch();
						PipelineTiming.increment("identityInsertBatches");
						pending = 0;
					}
				}
				if (pending > 0) {
					statement.executeBatch();
					PipelineTiming.increment("identityInsertBatches");
				}
			}
		});
	}
}
