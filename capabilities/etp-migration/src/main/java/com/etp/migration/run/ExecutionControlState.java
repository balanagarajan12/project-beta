package com.etp.migration.run;

/**
 * Operator control, stored separately from run and promotion lifecycle status.
 * {@code PAUSED} means the worker has stopped claiming and no batch transaction is still open for that control.
 */
public enum ExecutionControlState {
	RUNNING,
	PAUSE_REQUESTED,
	PAUSED,
	RESUMING
}
