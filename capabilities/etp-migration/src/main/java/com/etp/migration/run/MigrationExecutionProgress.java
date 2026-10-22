package com.etp.migration.run;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Last time a run reported bytes read, a finished stage, or a completed batch.
 * A live thread does not update this. The database lease stays the ownership lock.
 */
@Component
public class MigrationExecutionProgress {

	private final ConcurrentHashMap<UUID, Instant> marks = new ConcurrentHashMap<>();

	public void mark(UUID runId) {
		marks.put(runId, Instant.now());
	}

	public void mark(UUID runId, Instant at) {
		marks.put(runId, at);
	}

	public void clear(UUID runId) {
		marks.remove(runId);
	}

	public boolean idleLongerThan(UUID runId, Duration lease, Instant now) {
		Instant mark = marks.get(runId);
		if (mark == null || lease == null) {
			return true;
		}
		return !mark.plus(lease).isAfter(now);
	}
}
