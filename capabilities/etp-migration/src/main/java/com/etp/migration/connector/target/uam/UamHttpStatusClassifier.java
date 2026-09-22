package com.etp.migration.connector.target.uam;

import com.etp.migration.processing.MigrationErrorCodes;

final class UamHttpStatusClassifier {

	private UamHttpStatusClassifier() {
	}

	static Classification classify(int status) {
		if (status >= 200 && status < 300) {
			return Classification.ok();
		}
		if (status == 401 || status == 403 || status == 404) {
			return Classification.infrastructure(MigrationErrorCodes.TARGET_CONFIGURATION_ERROR, false);
		}
		if (status == 429) {
			return Classification.record(MigrationErrorCodes.TARGET_4XX, true);
		}
		if (status >= 500) {
			return Classification.record(MigrationErrorCodes.TARGET_5XX, true);
		}
		return Classification.record(MigrationErrorCodes.TARGET_4XX, false);
	}

	record Classification(boolean successful, boolean infrastructure, String errorCode, boolean retryable) {

		static Classification ok() {
			return new Classification(true, false, null, false);
		}

		static Classification infrastructure(String errorCode, boolean retryable) {
			return new Classification(false, true, errorCode, retryable);
		}

		static Classification record(String errorCode, boolean retryable) {
			return new Classification(false, false, errorCode, retryable);
		}
	}
}
