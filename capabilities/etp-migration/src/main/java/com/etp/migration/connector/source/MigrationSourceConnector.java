package com.etp.migration.connector.source;

import com.etp.migration.entity.MigrationBatch;
import com.etp.migration.entity.MigrationRun;
import com.etp.migration.entity.MigrationSource;

public interface MigrationSourceConnector {

	boolean supports(MigrationSource source);

	SourceInspection inspect(MigrationSource source, MigrationRun run);

	SourceBatch readBatch(MigrationSource source, MigrationBatch batch);
}
