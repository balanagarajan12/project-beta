package com.etp.migration.connector.target;

import com.etp.migration.entity.LoadMode;
import java.util.List;

/**
 * Generic target loading contract. Implementations must not be invoked with UAM-specific types
 * from the migration worker — only {@link TargetLoadResult} is returned to the engine.
 */
public interface MigrationTargetConnector {

	String targetType();

	boolean supportsLoadMode(LoadMode loadMode);

	TargetLoadResult load(MigrationTargetContext context, List<LoadableTargetRecord> records);
}
