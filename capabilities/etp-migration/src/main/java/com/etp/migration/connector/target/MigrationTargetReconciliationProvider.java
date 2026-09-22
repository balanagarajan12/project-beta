package com.etp.migration.connector.target;

import java.util.Map;
import java.util.UUID;

public interface MigrationTargetReconciliationProvider {

	String targetType();

	TargetVerification verify(UUID migrationRunId, Map<String, Object> definitionConfiguration);
}
