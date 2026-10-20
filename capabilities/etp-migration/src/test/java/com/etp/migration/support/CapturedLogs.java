package com.etp.migration.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.slf4j.LoggerFactory;

/**
 * Captures events for the given loggers, copying the MDC at the moment each event is logged.
 */
public final class CapturedLogs extends AppenderBase<ILoggingEvent> implements AutoCloseable {

	private final List<Logger> loggers = new ArrayList<>();
	private final List<Entry> entries = Collections.synchronizedList(new ArrayList<>());

	private CapturedLogs() {
	}

	public static CapturedLogs of(Class<?>... types) {
		CapturedLogs capture = new CapturedLogs();
		capture.start();
		for (Class<?> type : types) {
			Logger logger = (Logger) LoggerFactory.getLogger(type);
			logger.addAppender(capture);
			capture.loggers.add(logger);
		}
		return capture;
	}

	@Override
	protected void append(ILoggingEvent event) {
		entries.add(new Entry(
				event.getLevel(),
				event.getFormattedMessage(),
				Map.copyOf(event.getMDCPropertyMap()),
				event.getThrowableProxy() != null));
	}

	public List<Entry> entries() {
		synchronized (entries) {
			return List.copyOf(entries);
		}
	}

	public List<Entry> containing(String text) {
		return entries().stream().filter(entry -> entry.message().contains(text)).toList();
	}

	@Override
	public void close() {
		loggers.forEach(logger -> logger.detachAppender(this));
		stop();
	}

	public record Entry(Level level, String message, Map<String, String> mdc, boolean hasThrowable) {
	}
}
