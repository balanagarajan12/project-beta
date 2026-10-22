package com.etp.migration.run;

import java.util.UUID;

public class PhaseJobNotFoundException extends RuntimeException {

	public PhaseJobNotFoundException(UUID jobId) {
		super("Phase job not found: " + jobId);
	}
}
