package com.etp.migration.run;

import com.etp.migration.config.ShutdownSignal;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Background pool for reconciliation and promotion jobs. It uses the same lease and
 * progress limits as migration execution and does not share that execution thread.
 * A live thread is not progress. The lease is renewed only after a progress report.
 */
@Component
public class MigrationPhaseCoordinator {

	private static final Logger log = LoggerFactory.getLogger(MigrationPhaseCoordinator.class);

	private final MigrationPhaseDriver driver;
	private final MigrationPhaseClaim claim;
	private final MigrationExecutionProgress progress;
	private final ShutdownSignal shutdownSignal;
	private final Duration progressInterval;
	private final ExecutorService executor;
	private final ConcurrentHashMap<UUID, Attempt> attempts = new ConcurrentHashMap<>();
	private final Set<UUID> recoveryQueued = ConcurrentHashMap.newKeySet();
	private final AtomicLong generations = new AtomicLong();
	private final boolean ownedExecutor;

	@Autowired
	public MigrationPhaseCoordinator(
			MigrationPhaseDriver driver,
			MigrationPhaseClaim claim,
			ShutdownSignal shutdownSignal,
			MigrationExecutionProperties properties,
			MigrationExecutionProgress progress) {
		this(driver, claim, shutdownSignal, progress, properties.progressInterval(), newPool(properties), true);
	}

	MigrationPhaseCoordinator(
			MigrationPhaseDriver driver,
			MigrationPhaseClaim claim,
			ShutdownSignal shutdownSignal,
			ExecutorService executor,
			MigrationExecutionProgress progress,
			Duration progressInterval) {
		this(driver, claim, shutdownSignal, progress, progressInterval, executor, false);
	}

	private MigrationPhaseCoordinator(
			MigrationPhaseDriver driver,
			MigrationPhaseClaim claim,
			ShutdownSignal shutdownSignal,
			MigrationExecutionProgress progress,
			Duration progressInterval,
			ExecutorService executor,
			boolean ownedExecutor) {
		this.driver = driver;
		this.claim = claim;
		this.shutdownSignal = shutdownSignal;
		this.progress = progress;
		this.progressInterval = progressInterval;
		this.executor = executor;
		this.ownedExecutor = ownedExecutor;
	}

	public void submit(UUID jobId) {
		submit(jobId, false);
	}

	private void submit(UUID jobId, boolean replacingStaleAttempt) {
		if (shutdownSignal.isShuttingDown()) {
			log.info("Phase scheduling deferred because the application is shutting down jobId={}", jobId);
			return;
		}
		Attempt attempt = new Attempt(generations.incrementAndGet(), Instant.now());
		if (replacingStaleAttempt) {
			attempts.put(jobId, attempt);
		} else if (attempts.putIfAbsent(jobId, attempt) != null) {
			log.info("Phase already scheduled jobId={}", jobId);
			return;
		}
		try {
			executor.execute(() -> {
				try {
					driver.drive(jobId);
				} finally {
					attempts.remove(jobId, attempt);
					if (!attempts.containsKey(jobId)) {
						recoveryQueued.remove(jobId);
					}
					progress.clear(jobId);
				}
			});
			log.info("Phase scheduled jobId={}", jobId);
		} catch (RejectedExecutionException ex) {
			attempts.remove(jobId, attempt);
			recoveryQueued.remove(jobId);
			progress.clear(jobId);
			log.error("Phase scheduling rejected jobId={}; the queued job stays QUEUED for recovery", jobId);
		}
	}

	@Scheduled(fixedDelayString = "${etp.migration.execution.recovery-interval-ms:15000}")
	public void recover() {
		if (shutdownSignal.isShuttingDown()) {
			return;
		}
		Instant now = Instant.now();
		for (var entry : attempts.entrySet()) {
			UUID jobId = entry.getKey();
			Attempt attempt = entry.getValue();
			if (!progress.idleLongerThan(jobId, progressInterval, now)) {
				if (claim.renew(jobId)) {
					log.info("Phase lease renewed after reported progress jobId={}", jobId);
				}
				continue;
			}
			if (attempt.submittedAt().plus(progressInterval).isAfter(now)) {
				log.info("Phase local worker has not progressed yet jobId={}", jobId);
				continue;
			}
			if (claim.holdsValidLease(jobId)) {
				log.warn(
						"Phase worker is silent but the lease is still valid jobId={}; another worker will not be started",
						jobId);
				continue;
			}
			if (!recoveryQueued.add(jobId)) {
				log.warn("Phase recovery is already queued jobId={}", jobId);
				continue;
			}
			log.warn("Phase lease expired without progress jobId={}; scheduling one recovery claim", jobId);
			submit(jobId, true);
		}
		for (UUID jobId : claim.recoverable(now)) {
			if (attempts.containsKey(jobId)) {
				continue;
			}
			log.info("Phase recovery candidate jobId={}", jobId);
			submit(jobId);
		}
	}

	@PreDestroy
	public void shutdown() {
		if (!ownedExecutor) {
			return;
		}
		executor.shutdown();
		try {
			if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
				log.warn("Background phase execution did not finish during shutdown");
			}
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	private static ExecutorService newPool(MigrationExecutionProperties properties) {
		return new ThreadPoolExecutor(
				properties.maxConcurrentRuns(),
				properties.maxConcurrentRuns(),
				0L,
				TimeUnit.MILLISECONDS,
				new ArrayBlockingQueue<>(properties.queueCapacity()),
				new PhaseThreadFactory(),
				new ThreadPoolExecutor.AbortPolicy());
	}

	private record Attempt(long generation, Instant submittedAt) {
	}

	private static final class PhaseThreadFactory implements ThreadFactory {
		private final AtomicInteger sequence = new AtomicInteger();

		@Override
		public Thread newThread(Runnable runnable) {
			Thread thread = new Thread(runnable, "migration-phase-" + sequence.incrementAndGet());
			thread.setDaemon(false);
			return thread;
		}
	}
}
