package com.etp.migration.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ShutdownSignalTest {

	@Test
	void signalIsRaisedWhenTheContextCloses() {
		AtomicReference<ShutdownSignal> captured = new AtomicReference<>();

		new ApplicationContextRunner().withBean(ShutdownSignal.class).run(context -> {
			ShutdownSignal signal = context.getBean(ShutdownSignal.class);
			assertThat(signal.isShuttingDown()).isFalse();
			captured.set(signal);
		});

		assertThat(captured.get().isShuttingDown()).isTrue();
	}
}
