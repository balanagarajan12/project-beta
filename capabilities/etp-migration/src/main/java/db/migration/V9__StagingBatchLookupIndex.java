package db.migration;

import com.etp.migration.connector.target.postgresql.PostgreSqlStagingIndexes;
import java.sql.Connection;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Builds the staging batch lookup index only when {@code migration_staging.uam_entity_stage}
 * is in the database Flyway is migrating. Staging is often a different database, and
 * {@code CREATE INDEX CONCURRENTLY} cannot run inside Flyway's transaction.
 */
public class V9__StagingBatchLookupIndex extends BaseJavaMigration {

	@Override
	public boolean canExecuteInTransaction() {
		return false;
	}

	@Override
	public void migrate(Context context) throws Exception {
		Connection connection = context.getConnection();
		connection.setAutoCommit(true);
		PostgreSqlStagingIndexes.createBatchLookupIfPresent(connection);
	}
}
