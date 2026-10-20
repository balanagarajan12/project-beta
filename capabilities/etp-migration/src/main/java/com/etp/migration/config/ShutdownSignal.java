package com.etp.migration.config;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Set when the application context starts closing. Spring publishes {@link ContextClosedEvent} before the web
 * server's graceful-shutdown wait, so in-flight requests can see it and stop claiming new work.
 */
@Component
public class ShutdownSignal {

	private final AtomicBoolean shuttingDown = new AtomicBoolean();

	@EventListener
	public void onContextClosed(ContextClosedEvent event) {
		shuttingDown.set(true);
	}

	public boolean isShuttingDown() {
		return shuttingDown.get();
	}
}
