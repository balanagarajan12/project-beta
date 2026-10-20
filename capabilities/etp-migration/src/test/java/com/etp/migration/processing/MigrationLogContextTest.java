package com.etp.migration.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

class MigrationLogContextTest {

	private static final UUID RUN = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID OTHER_RUN = UUID.fromString("22222222-2222-2222-2222-222222222222");

	@AfterEach
	void clearMdc() {
		MDC.clear();
	}

	@Test
	void batchScopeSetsBothIdentifiersAndRemovesThemOnClose() {
		try (MigrationLogContext ignored = MigrationLogContext.forBatch(RUN, 7L)) {
			assertThat(MDC.get(MigrationLogContext.RUN_ID)).isEqualTo(RUN.toString());
			assertThat(MDC.get(MigrationLogContext.BATCH_ID)).isEqualTo("7");
		}

		assertThat(MDC.get(MigrationLogContext.RUN_ID)).isNull();
		assertThat(MDC.get(MigrationLogContext.BATCH_ID)).isNull();
	}

	@Test
	void nestedBatchScopeRestoresTheEnclosingRunScope() {
		MDC.put("requestId", "keep-me");
		try (MigrationLogContext run = MigrationLogContext.forRun(RUN)) {
			try (MigrationLogContext batch = MigrationLogContext.forBatch(OTHER_RUN, 9L)) {
				assertThat(MDC.get(MigrationLogContext.RUN_ID)).isEqualTo(OTHER_RUN.toString());
			}
			assertThat(MDC.get(MigrationLogContext.RUN_ID)).isEqualTo(RUN.toString());
			assertThat(MDC.get(MigrationLogContext.BATCH_ID)).isNull();
		}
		assertThat(MDC.get(MigrationLogContext.RUN_ID)).isNull();
		assertThat(MDC.get("requestId")).isEqualTo("keep-me");
	}

	@Test
	void identifiersAreRemovedWhenTheScopedWorkThrows() {
		assertThatThrownBy(() -> {
			try (MigrationLogContext ignored = MigrationLogContext.forBatch(RUN, 3L)) {
				throw new IllegalStateException("boom");
			}
		}).hasMessage("boom");

		assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
	}

	@Test
	void aReusedPoolThreadDoesNotInheritIdentifiersFromAFailedTask() throws Exception {
		ExecutorService singleThread = Executors.newSingleThreadExecutor();
		try {
			singleThread.submit(() -> {
				try (MigrationLogContext ignored = MigrationLogContext.forBatch(RUN, 5L)) {
					throw new IllegalStateException("task failed");
				}
			});
			var seenByNextTask = singleThread.submit(() -> MDC.getCopyOfContextMap()).get(10, TimeUnit.SECONDS);

			assertThat(seenByNextTask).isNullOrEmpty();
		} finally {
			singleThread.shutdownNow();
		}
	}

	@Test
	void configuredCorrelationPatternPrintsIdentifiersOnlyWhenPresent() throws Exception {
		Properties properties = new Properties();
		try (Reader reader = Files.newBufferedReader(Path.of("src/main/resources/application.properties"))) {
			properties.load(reader);
		}
		String correlation = properties.getProperty("logging.pattern.correlation");
		LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
		PatternLayout layout = new PatternLayout();
		layout.setContext(context);
		layout.setPattern("[" + correlation + "]%m");
		layout.start();
		ch.qos.logback.classic.Logger logger = context.getLogger(MigrationLogContextTest.class);

		String outside = layout.doLayout(new LoggingEvent("fqcn", logger, ch.qos.logback.classic.Level.INFO, "x", null, null));
		String inBatch;
		try (MigrationLogContext ignored = MigrationLogContext.forBatch(RUN, 42L)) {
			inBatch = layout.doLayout(new LoggingEvent("fqcn", logger, ch.qos.logback.classic.Level.INFO, "x", null, null));
		}
		String inRun;
		try (MigrationLogContext ignored = MigrationLogContext.forRun(RUN)) {
			inRun = layout.doLayout(new LoggingEvent("fqcn", logger, ch.qos.logback.classic.Level.INFO, "x", null, null));
		}

		assertThat(outside).isEqualTo("[]x");
		assertThat(inBatch).isEqualTo("[run=" + RUN + " batch=42 ]x");
		assertThat(inRun).isEqualTo("[run=" + RUN + " ]x");
	}
}
