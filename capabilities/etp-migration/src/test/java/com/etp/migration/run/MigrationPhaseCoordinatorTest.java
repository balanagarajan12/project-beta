package com.etp.migration.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.etp.migration.config.ShutdownSignal;
import java.time.Duration;
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
class MigrationPhaseCoordinatorTest {

	private static final UUID JOB_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

	@Mock
	private MigrationPhaseDriver driver;
	@Mock
	private MigrationPhaseClaim claim;

	private final ShutdownSignal shutdownSignal = new ShutdownSignal();
	private final MigrationExecutionProgress progress = new MigrationExecutionProgress();
	private ExecutorService executor;

	@AfterEach
	void stopPool() {
		if (executor != null) {
			executor.shutdownNow();
		}
	}

	@Test
	void validLeaseDoesNotStartASecondWorkerWhenProgressIsSilent() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger drives = new AtomicInteger();
		executor = Executors.newFixedThreadPool(2);
		doAnswer(invocation -> {
			drives.incrementAndGet();
			entered.countDown();
			assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
			return null;
		}).when(driver).drive(JOB_ID);
		when(claim.holdsValidLease(JOB_ID)).thenReturn(true);
		when(claim.recoverable(any())).thenReturn(List.of());
		MigrationPhaseCoordinator coordinator = coordinator(Duration.ofMillis(1));

		coordinator.submit(JOB_ID);
		assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
		Thread.sleep(30);
		coordinator.recover();
		coordinator.recover();

		assertThat(drives).hasValue(1);
		verify(claim, org.mockito.Mockito.atLeastOnce()).holdsValidLease(JOB_ID);
		verify(driver, timeout(500).times(1)).drive(JOB_ID);
		release.countDown();
	}

	@Test
	void expiredLeaseQueuesOnlyOneRecovery() throws Exception {
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
		}).when(driver).drive(JOB_ID);
		when(claim.holdsValidLease(JOB_ID)).thenReturn(false);
		when(claim.recoverable(any())).thenReturn(List.of());
		MigrationPhaseCoordinator coordinator = coordinator(Duration.ofMillis(1));

		coordinator.submit(JOB_ID);
		assertThat(firstEntered.await(2, TimeUnit.SECONDS)).isTrue();
		Thread.sleep(30);
		coordinator.recover();
		coordinator.recover();

		assertThat(secondEntered.await(2, TimeUnit.SECONDS)).isTrue();
		assertThat(drives).hasValue(2);
		release.countDown();
	}

	@Test
	void rejectedSchedulingLeavesTheJobRecoverable() {
		AtomicInteger attempts = new AtomicInteger();
		executor = new java.util.concurrent.ThreadPoolExecutor(
				1, 1, 0L, TimeUnit.MILLISECONDS, new java.util.concurrent.ArrayBlockingQueue<>(1)) {
			@Override
			public void execute(Runnable command) {
				if (attempts.getAndIncrement() == 0) {
					throw new RejectedExecutionException("full");
				}
				super.execute(command);
			}
		};
		when(claim.recoverable(any())).thenReturn(List.of(JOB_ID));
		MigrationPhaseCoordinator coordinator = coordinator(Duration.ofMinutes(5));

		coordinator.submit(JOB_ID);
		verify(driver, never()).drive(JOB_ID);

		coordinator.recover();

		verify(driver, timeout(2_000)).drive(JOB_ID);
	}

	private MigrationPhaseCoordinator coordinator(Duration progressInterval) {
		return new MigrationPhaseCoordinator(
				driver, claim, shutdownSignal, executor, progress, progressInterval);
	}
}
