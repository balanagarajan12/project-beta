package com.etp.migration.run;

public enum PhaseJobStatus {
	QUEUED,
	RUNNING,
	COMPLETED,
	FAILED;

	public boolean isTerminal() {
		return this == COMPLETED || this == FAILED;
	}
}
