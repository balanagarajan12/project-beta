package com.etp.migration.connector.target;

import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public interface MigrationTargetReconciliationProvider {

	String targetType();

	TargetVerification verify(UUID migrationRunId, Map<String, Object> definitionConfiguration);

	/**
	 * Streams staged entity rows for one run. The default supplies no rows.
	 * A missing projection is unavailable. It is not a zero count.
	 */
	default boolean entityProjectionAvailable() {
		return false;
	}

	default void streamStagedEntities(
			UUID migrationRunId,
			Map<String, Object> definitionConfiguration,
			Consumer<StagedEntityView> consumer) {
	}
}
