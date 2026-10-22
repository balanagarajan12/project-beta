package com.etp.migration.run;

import java.util.UUID;

/**
 * The worker lost the phase lease before the next unit of work.
 * The job is left for the worker that now owns it. This is not a failure of the phase.
 */
public class PhaseOwnershipLostException extends RuntimeException {

	public PhaseOwnershipLostException(UUID runId) {
		super("Phase worker no longer owns run " + runId);
	}
}
