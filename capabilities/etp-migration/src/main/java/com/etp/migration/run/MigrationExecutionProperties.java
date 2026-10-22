package com.etp.migration.run;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MigrationExecutionProperties {

	private final int maxConcurrentRuns;
	private final int queueCapacity;
	private final Duration leaseDuration;
	private final Duration progressInterval;
	private final long recoveryIntervalMs;

	public MigrationExecutionProperties(
			@Value("${etp.migration.execution.max-concurrent-runs:1}") int maxConcurrentRuns,
			@Value("${etp.migration.execution.queue-capacity:32}") int queueCapacity,
			@Value("${etp.migration.execution.lease-duration:PT5M}") Duration leaseDuration,
			@Value("${etp.migration.execution.progress-interval:PT30S}") Duration progressInterval,
			@Value("${etp.migration.execution.recovery-interval-ms:15000}") long recoveryIntervalMs) {
		if (maxConcurrentRuns < 1) {
			throw new IllegalArgumentException("etp.migration.execution.max-concurrent-runs must be at least 1");
		}
		if (queueCapacity < 1) {
			throw new IllegalArgumentException("etp.migration.execution.queue-capacity must be at least 1");
		}
		if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
			throw new IllegalArgumentException("etp.migration.execution.lease-duration must be positive");
		}
		if (progressInterval == null || progressInterval.isZero() || progressInterval.isNegative()) {
			throw new IllegalArgumentException("etp.migration.execution.progress-interval must be positive");
		}
		if (progressInterval.compareTo(leaseDuration) > 0) {
			throw new IllegalArgumentException(
					"etp.migration.execution.progress-interval must not exceed etp.migration.execution.lease-duration");
		}
		this.maxConcurrentRuns = maxConcurrentRuns;
		this.queueCapacity = queueCapacity;
		this.leaseDuration = leaseDuration;
		this.progressInterval = progressInterval;
		this.recoveryIntervalMs = recoveryIntervalMs;
	}

	public int maxConcurrentRuns() {
		return maxConcurrentRuns;
	}

	public int queueCapacity() {
		return queueCapacity;
	}

	public Duration leaseDuration() {
		return leaseDuration;
	}

	public Duration progressInterval() {
		return progressInterval;
	}

	public long recoveryIntervalMs() {
		return recoveryIntervalMs;
	}
}
