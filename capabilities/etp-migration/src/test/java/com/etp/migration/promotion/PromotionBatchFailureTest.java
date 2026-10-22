package com.etp.migration.promotion;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.etp.migration.entity.MigrationPromotionBatch;
import com.etp.migration.entity.PromotionBatchStatus;
import com.etp.migration.processing.MigrationErrorCodes;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class PromotionBatchFailureTest {

	@Test
	void nullPointerWithNoMessageRecordsTheClassAndFrame() {
		NullPointerException failure = new NullPointerException();
		failure.setStackTrace(new StackTraceElement[] {
				new StackTraceElement(
						"com.etp.migration.connector.target.uam.UamCanonicalRows",
						"referencedValue",
						"UamCanonicalRows.java",
						146)
		});
		MigrationPromotionBatch batch = failingBatch();

		PromotionExecutionService.recordFailure(batch, 1000L, failure);

		assertThat(batch.getStatus()).isEqualTo(PromotionBatchStatus.FAILED);
		assertThat(batch.getErrorCode()).isEqualTo(MigrationErrorCodes.PROMOTION_BATCH_FAILED);
		assertThat(batch.getPromotedRecordCount()).isZero();
		assertThat(batch.getFailedRecordCount()).isEqualTo(1000L);
		assertThat(batch.getCompletedAt()).isNotNull();
		assertThat(batch.getErrorMessage()).isEqualTo(
				"java.lang.NullPointerException at "
						+ "com.etp.migration.connector.target.uam.UamCanonicalRows.referencedValue(UamCanonicalRows.java:146)");
	}

	@Test
	void nonblankMessageIsPreserved() {
		IllegalStateException failure = new IllegalStateException("Canonical parent row is not in the target");
		MigrationPromotionBatch batch = failingBatch();

		PromotionExecutionService.recordFailure(batch, 4L, failure);

		assertThat(batch.getStatus()).isEqualTo(PromotionBatchStatus.FAILED);
		assertThat(batch.getErrorCode()).isEqualTo(MigrationErrorCodes.PROMOTION_BATCH_FAILED);
		assertThat(batch.getPromotedRecordCount()).isZero();
		assertThat(batch.getFailedRecordCount()).isEqualTo(4L);
		assertThat(batch.getErrorMessage()).isEqualTo("Canonical parent row is not in the target");
	}

	@Test
	void blankMessageWithoutAStackFrameUsesTheExceptionClass() {
		IllegalStateException failure = new IllegalStateException("  ");
		failure.setStackTrace(new StackTraceElement[0]);
		MigrationPromotionBatch batch = failingBatch();

		PromotionExecutionService.recordFailure(batch, 1L, failure);

		assertThat(batch.getErrorCode()).isEqualTo(MigrationErrorCodes.PROMOTION_BATCH_FAILED);
		assertThat(batch.getErrorMessage()).isEqualTo("java.lang.IllegalStateException");
	}

	@Test
	void databaseFailureKeepsTheStructuralSummaryAndDropsTheServerText() {
		RuntimeException failure = new NullMessage(new SQLException("secret row value", "23503"));

		assertThat(PromotionExecutionService.failureMessage(failure))
				.isEqualTo("Promotion batch failed: foreign_key_violation (SQLState 23503)")
				.doesNotContain("secret");
	}

	@Test
	void failureLogKeepsTheOriginalException() {
		Logger logger = (Logger) LoggerFactory.getLogger(PromotionExecutionService.class);
		ListAppender<ILoggingEvent> events = new ListAppender<>();
		events.start();
		logger.addAppender(events);
		NullPointerException failure = new NullPointerException();
		try {
			PromotionExecutionService.logBatchFailure(15L, 1501L, failure);
		} finally {
			logger.detachAppender(events);
		}

		assertThat(events.list).singleElement().satisfies(event -> {
			assertThat(event.getFormattedMessage())
					.isEqualTo("Promotion batch failed promotionBatchId=15 migrationBatchId=1501 cause=NullPointerException");
			assertThat(event.getThrowableProxy().getClassName()).isEqualTo(NullPointerException.class.getName());
			assertThat(event.getThrowableProxy().getStackTraceElementProxyArray()).isNotEmpty();
		});
	}

	private static final class NullMessage extends RuntimeException {
		private NullMessage(Throwable cause) {
			super(null, cause, false, false);
		}
	}

	private static MigrationPromotionBatch failingBatch() {
		MigrationPromotionBatch batch = new MigrationPromotionBatch();
		batch.setStatus(PromotionBatchStatus.PROMOTING);
		batch.setPromotedRecordCount(3L);
		batch.setFailedRecordCount(0L);
		return batch;
	}
}
