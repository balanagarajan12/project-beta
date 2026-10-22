package com.etp.migration.run;

import com.etp.migration.config.ShutdownSignal;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
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
 * Bounded background execution for accepted runs. The database lease is the source of truth.
 * This pool only decides which JVM is trying to claim a run. A local marker remembers the attempt
 * so a live worker is not started twice. The marker records when that attempt was submitted, so a
 * worker that stops reporting progress can be claimed again without dropping a newer attempt.
 * Lease renewal follows a progress report. A live thread, or a lease renewed earlier, is not progress.
 */
@Component
public class MigrationExecutionCoordinator {

	private static final Logger log = LoggerFactory.getLogger(MigrationExecutionCoordinator.class);

	private final MigrationRunDriver driver;
	private final MigrationExecutionClaim claim;
	private final MigrationExecutionProgress progress;
	private final ShutdownSignal shutdownSignal;
	private final Duration progressInterval;
	private final ExecutorService executor;
	private final ConcurrentHashMap<UUID, Attempt> attempts = new ConcurrentHashMap<>();
	private final AtomicLong generations = new AtomicLong();
	private final boolean ownedExecutor;

	@Autowired
	public MigrationExecutionCoordinator(
			MigrationRunDriver driver,
			MigrationExecutionClaim claim,
			ShutdownSignal shutdownSignal,
			MigrationExecutionProperties properties,
			MigrationExecutionProgress progress) {
		this(driver, claim, shutdownSignal, progress, properties.progressInterval(), newPool(properties), true);
	}

	MigrationExecutionCoordinator(
			MigrationRunDriver driver,
			MigrationExecutionClaim claim,
			ShutdownSignal shutdownSignal,
			ExecutorService executor,
			MigrationExecutionProgress progress,
			Duration progressInterval) {
		this(driver, claim, shutdownSignal, progress, progressInterval, executor, false);
	}

	private MigrationExecutionCoordinator(
			MigrationRunDriver driver,
			MigrationExecutionClaim claim,
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

	public void submit(UUID runId) {
		submit(runId, false);
	}

	private void submit(UUID runId, boolean replacingStaleAttempt) {
		if (shutdownSignal.isShuttingDown()) {
			log.info("Run scheduling deferred because the application is shutting down runId={}", runId);
			return;
		}
		long generation = generations.incrementAndGet();
		Attempt attempt = new Attempt(generation, Instant.now());
		if (replacingStaleAttempt) {
			attempts.put(runId, attempt);
		} else if (attempts.putIfAbsent(runId, attempt) != null) {
			log.info("Run already scheduled runId={}", runId);
			return;
		}
		try {
			executor.execute(() -> {
				try {
					driver.drive(runId);
				} finally {
					attempts.remove(runId, attempt);
					progress.clear(runId);
				}
			});
			log.info("Run scheduled runId={}", runId);
		} catch (RejectedExecutionException ex) {
			attempts.remove(runId, attempt);
			progress.clear(runId);
			log.error("Run scheduling rejected runId={}; recovery will retry", runId);
		}
	}

	@Scheduled(fixedDelayString = "${etp.migration.execution.recovery-interval-ms:15000}")
	public void recover() {
		if (shutdownSignal.isShuttingDown()) {
			return;
		}
		Instant now = Instant.now();
		for (var entry : attempts.entrySet()) {
			UUID runId = entry.getKey();
			Attempt attempt = entry.getValue();
			if (!progress.idleLongerThan(runId, progressInterval, now)) {
				if (claim.renew(runId)) {
					log.info("Run lease renewed after reported progress runId={}", runId);
				}
				continue;
			}
			if (attempt.submittedAt().plus(progressInterval).isAfter(now)) {
				log.info("Run local worker has not progressed yet runId={}", runId);
				continue;
			}
			log.warn("Run local worker stopped progressing runId={}; scheduling another claim", runId);
			submit(runId, true);
		}
		for (UUID runId : claim.recoverable(now)) {
			if (attempts.containsKey(runId)) {
				continue;
			}
			log.info("Run recovery candidate runId={}", runId);
			submit(runId);
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
				log.warn("Background migration execution did not finish during shutdown");
			}
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	private static ExecutorService newPool(MigrationExecutionProperties properties) {
		ThreadPoolExecutor pool = new ThreadPoolExecutor(
				properties.maxConcurrentRuns(),
				properties.maxConcurrentRuns(),
				0L,
				TimeUnit.MILLISECONDS,
				new ArrayBlockingQueue<>(properties.queueCapacity()),
				new ExecutionThreadFactory(),
				new ThreadPoolExecutor.AbortPolicy());
		return pool;
	}

	private record Attempt(long generation, Instant submittedAt) {
	}

	private static final class ExecutionThreadFactory implements ThreadFactory {
		private final AtomicInteger sequence = new AtomicInteger();

		@Override
		public Thread newThread(Runnable runnable) {
			Thread thread = new Thread(runnable, "migration-execution-" + sequence.incrementAndGet());
			thread.setDaemon(false);
			return thread;
		}
	}
}
