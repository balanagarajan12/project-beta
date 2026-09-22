package com.etp.migration.run;

import com.etp.migration.entity.MigrationConfigStatus;
import java.util.UUID;

public class DefinitionNotActiveException extends RuntimeException {

	public DefinitionNotActiveException(UUID definitionId, MigrationConfigStatus status) {
		super("Migration definition " + definitionId + " is " + status + " and cannot start a run");
	}
}
