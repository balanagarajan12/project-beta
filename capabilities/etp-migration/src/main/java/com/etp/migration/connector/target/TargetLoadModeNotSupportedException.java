package com.etp.migration.connector.target;

import com.etp.migration.entity.LoadMode;
import com.etp.migration.processing.MigrationErrorCodes;

public class TargetLoadModeNotSupportedException extends RuntimeException {

	private final String errorCode = MigrationErrorCodes.TARGET_LOAD_MODE_NOT_SUPPORTED;

	public TargetLoadModeNotSupportedException(String targetType, LoadMode loadMode) {
		super("Load mode " + loadMode + " is not supported for target type " + targetType);
	}

	public String errorCode() {
		return errorCode;
	}
}
