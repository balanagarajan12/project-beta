package com.etp.migration.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.etp.migration.config.ShutdownSignal;
import com.etp.migration.entity.MigrationRunStatus;
import com.etp.migration.run.dto.CreateMigrationRunRequest;
import com.etp.migration.run.dto.MigrationRunResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MigrationExecutionCoordinatorTest {

	private static final UUID DEFINITION_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
	private static final UUID RUN_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

	@Mock
	private MigrationRunDriver driver;
	@Mock
	private MigrationExecutionClaim claim;
	@Mock
	private MigrationRunService migrationRunService;

	private final ShutdownSignal shutdownSignal = new ShutdownSignal();
	private ExecutorService executor;

	@AfterEach
	void stopPool() {
		if (executor != null) {
			executor.shutdownNow();
		}
	}

	@Test
	void executeReturnsBeforeBackgroundWorkFinishes() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		executor = Executors.newSingleThreadExecutor();
		doAnswer(invocation -> {
			entered.countDown();
			assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
			return null;
		}).when(driver).drive(RUN_ID);
		when(migrationRunService.create(org.mockito.ArgumentMatchers.eq(DEFINITION_ID), org.mockito.ArgumentMatchers.any()))
				.thenReturn(queued());
		MigrationExecutionCoordinator coordinator = coordinator(executor);
		MigrationExecutionService service = new MigrationExecutionService(migrationRunService, coordinator, org.mockito.Mockito.mock(ExecutionControlService.class));

		assertThatCode(() -> org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2),
				() -> service.execute(DEFINITION_ID, new CreateMigrationRunRequest(null, null, null))))
				.doesNotThrowAnyException();
		assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
		verify(migrationRunService).requestBackgroundExecution(RUN_ID);
		release.countDown();
	}

	@Test
	void rejectedSchedulingLeavesTheAcceptedRunForRecovery() {
		when(migrationRunService.create(org.mockito.ArgumentMatchers.eq(DEFINITION_ID), org.mockito.ArgumentMatchers.any()))
				.thenReturn(queued());
		ExecutorService rejecting = new java.util.concurrent.ThreadPoolExecutor(
				1, 1, 0L, TimeUnit.MILLISECONDS, new java.util.concurrent.ArrayBlockingQueue<>(1)) {
			@Override
			public void execute(Runnable command) {
				throw new RejectedExecutionException("full");
			}
		};
		executor = rejecting;
		MigrationExecutionCoordinator coordinator = new MigrationExecutionCoordinator(
				driver, claim, shutdownSignal, rejecting, new MigrationExecutionProgress(), Duration.ofMinutes(5));
		MigrationExecutionService service = new MigrationExecutionService(migrationRunService, coordinator, org.mockito.Mockito.mock(ExecutionControlService.class));

		MigrationRunResponse response = service.execute(DEFINITION_ID, null);

		assertThat(response.status()).isEqualTo(MigrationRunStatus.QUEUED);
		assertThat(response.id()).isEqualTo(RUN_ID);
		verify(migrationRunService).requestBackgroundExecution(RUN_ID);
		verify(driver, never()).drive(RUN_ID);
	}

	@Test
	void duplicateSubmitDoesNotDriveTheSameRunConcurrently() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger drives = new AtomicInteger();
		executor = Executors.newFixedThreadPool(2);
		doAnswer(invocation -> {
			drives.incrementAndGet();
			entered.countDown();
			assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
			return null;
		}).when(driver).drive(RUN_ID);
		MigrationExecutionCoordinator coordinator = coordinator(executor);

		coordinator.submit(RUN_ID);
		coordinator.submit(RUN_ID);
		assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
		assertThat(drives).hasValue(1);
		release.countDown();
	}

	@Test
	void recoverySubmitsAnEligibleRun() {
		executor = Executors.newSingleThreadExecutor();
		when(claim.recoverable(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(RUN_ID));
		MigrationExecutionCoordinator coordinator = coordinator(executor);

		coordinator.recover();

		verify(driver, timeout(2_000)).drive(RUN_ID);
	}

	@Test
	void shutdownDoesNotInterruptADriveThatAlreadyStarted() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		MigrationExecutionProperties properties = new MigrationExecutionProperties(
				1, 4, Duration.ofMinutes(5), Duration.ofSeconds(30), 60_000);
		doAnswer(invocation -> {
			entered.countDown();
			assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
			return null;
		}).when(driver).drive(RUN_ID);
		MigrationExecutionCoordinator coordinator = new MigrationExecutionCoordinator(
				driver, claim, shutdownSignal, properties, new MigrationExecutionProgress());

		coordinator.submit(RUN_ID);
		assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
		Thread stopper = new Thread(() -> {
			release.countDown();
			coordinator.shutdown();
		});
		stopper.start();
		stopper.join(5_000);
		verify(driver).drive(RUN_ID);
	}

	@Test
	void reportedProgressRenewsTheLeaseInsteadOfStartingAgain() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		executor = Executors.newFixedThreadPool(2);
		doAnswer(invocation -> {
			progress.mark(RUN_ID);
			entered.countDown();
			assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
			return null;
		}).when(driver).drive(RUN_ID);
		when(claim.renew(RUN_ID)).thenReturn(true);
		when(claim.recoverable(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(RUN_ID));
		MigrationExecutionCoordinator coordinator = coordinator(executor);

		coordinator.submit(RUN_ID);
		assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
		coordinator.recover();

		verify(driver, timeout(1_000).times(1)).drive(RUN_ID);
		verify(claim, timeout(1_000)).renew(RUN_ID);
		release.countDown();
	}

	@Test
	void stalledLocalWorkerCanBeClaimedAgain() throws Exception {
		CountDownLatch firstEntered = new CountDownLatch(1);
		CountDownLatch secondEntered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger drives = new AtomicInteger();
		executor = Executors.newFixedThreadPool(2);
		doAnswer(invocation -> {
			int call = drives.incrementAndGet();
			if (call == 1) {
				firstEntered.countDown();
			} else {
				secondEntered.countDown();
			}
			assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
			return null;
		}).when(driver).drive(RUN_ID);
		when(claim.recoverable(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(RUN_ID));
		MigrationExecutionCoordinator coordinator = new MigrationExecutionCoordinator(
				driver, claim, shutdownSignal, executor, progress, Duration.ofMillis(1));

		coordinator.submit(RUN_ID);
		assertThat(firstEntered.await(2, TimeUnit.SECONDS)).isTrue();
		progress.mark(RUN_ID, Instant.now().minus(Duration.ofMinutes(5)));
		Thread.sleep(5);
		coordinator.recover();

		assertThat(secondEntered.await(2, TimeUnit.SECONDS)).isTrue();
		assertThat(drives.get()).isGreaterThanOrEqualTo(2);
		verify(claim, never()).renew(RUN_ID);
		release.countDown();
	}

	@Test
	void liveWorkerWithoutProgressIsNotRenewedAndCanBeClaimedAgain() throws Exception {
		CountDownLatch firstEntered = new CountDownLatch(1);
		CountDownLatch secondEntered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger drives = new AtomicInteger();
		executor = Executors.newFixedThreadPool(2);
		doAnswer(invocation -> {
			int call = drives.incrementAndGet();
			if (call == 1) {
				firstEntered.countDown();
			} else {
				secondEntered.countDown();
			}
			assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
			return null;
		}).when(driver).drive(RUN_ID);
		when(claim.recoverable(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(RUN_ID));
		MigrationExecutionCoordinator coordinator = new MigrationExecutionCoordinator(
				driver, claim, shutdownSignal, executor, progress, Duration.ofMillis(1));

		coordinator.submit(RUN_ID);
		assertThat(firstEntered.await(2, TimeUnit.SECONDS)).isTrue();
		Thread.sleep(5);
		coordinator.recover();

		assertThat(secondEntered.await(2, TimeUnit.SECONDS)).isTrue();
		verify(claim, never()).renew(RUN_ID);
		release.countDown();
	}

	@Test
	void progressIntervalLongerThanTheLeaseIsRejected() {
		assertThatThrownBy(() -> new MigrationExecutionProperties(
				1, 4, Duration.ofSeconds(30), Duration.ofMinutes(5), 15_000))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("progress-interval");
	}

	private final MigrationExecutionProgress progress = new MigrationExecutionProgress();

	private MigrationExecutionCoordinator coordinator(ExecutorService pool) {
		return new MigrationExecutionCoordinator(
				driver, claim, shutdownSignal, pool, progress, Duration.ofMinutes(5));
	}

	private static MigrationRunResponse queued() {
		Instant now = Instant.parse("2026-09-21T00:00:00Z");
		return new MigrationRunResponse(
				RUN_ID, DEFINITION_ID, 1L, null, MigrationRunStatus.QUEUED, null, null,
				0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
				0, 0, 0,
				"system", now, now);
	}
}
