package com.etp.migration.batch;

import com.etp.migration.run.InvalidStateTransitionException;

public class BatchClaimLostException extends InvalidStateTransitionException {

	public BatchClaimLostException(Long batchId) {
		super("Batch " + batchId + " is no longer claimed by this worker");
	}
}
