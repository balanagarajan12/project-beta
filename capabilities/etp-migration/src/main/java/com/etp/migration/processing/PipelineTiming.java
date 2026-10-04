package com.etp.migration.processing;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bounded stage timings and counters for one pipeline pass.
 * Nothing is recorded per source row. A pass with no bound timing does no extra work
 * beyond a thread-local lookup at each instrumented boundary.
 */
public final class PipelineTiming {

	private static final Logger log = LoggerFactory.getLogger(PipelineTiming.class);
	private static final ThreadLocal<PipelineTiming> CURRENT = new ThreadLocal<>();

	private final EnumMap<Stage, Long> nanos = new EnumMap<>(Stage.class);
	private final Map<String, Long> counters = new LinkedHashMap<>();

	public static PipelineTiming current() {
		return CURRENT.get();
	}

	public static void bind(PipelineTiming timing) {
		if (timing == null) {
			CURRENT.remove();
		} else {
			CURRENT.set(timing);
		}
	}

	/**
	 * Records into the timing already bound by the caller.
	 * When none is bound, creates one, logs it, and removes it.
	 */
	public static void open(Runnable work) {
		if (CURRENT.get() != null) {
			work.run();
			return;
		}
		PipelineTiming created = new PipelineTiming();
		CURRENT.set(created);
		try {
			work.run();
		} finally {
			CURRENT.remove();
			log.info("Pipeline timing {}", created.toMap());
		}
	}

	public static <T> T time(Stage stage, Supplier<T> work) {
		long started = start();
		try {
			return work.get();
		} finally {
			stop(stage, started);
		}
	}

	public static long start() {
		return CURRENT.get() == null ? -1L : System.nanoTime();
	}

	public static void stop(Stage stage, long started) {
		if (started < 0L) {
			return;
		}
		add(stage, Math.max(0L, System.nanoTime() - started));
	}

	public static void add(Stage stage, long elapsedNanos) {
		PipelineTiming timing = CURRENT.get();
		if (timing == null || elapsedNanos < 0L) {
			return;
		}
		timing.nanos.merge(stage, elapsedNanos, Long::sum);
	}

	public static void increment(String counter) {
		PipelineTiming timing = CURRENT.get();
		if (timing == null || counter == null) {
			return;
		}
		timing.counters.merge(counter, 1L, Long::sum);
	}

	public static void set(String counter, long value) {
		PipelineTiming timing = CURRENT.get();
		if (timing == null || counter == null) {
			return;
		}
		timing.counters.put(counter, value);
	}

	public static void max(String counter, long value) {
		PipelineTiming timing = CURRENT.get();
		if (timing == null || counter == null) {
			return;
		}
		timing.counters.merge(counter, value, Math::max);
	}

	public long millis(Stage stage) {
		return nanos.getOrDefault(stage, 0L) / 1_000_000L;
	}

	public long counter(String name) {
		return counters.getOrDefault(name, 0L);
	}

	public Map<String, Object> toMap() {
		Map<String, Object> map = new LinkedHashMap<>();
		for (Stage stage : Stage.values()) {
			map.put(stage.jsonName(), millis(stage));
		}
		map.putAll(counters);
		return map;
	}

	public enum Stage {
		SOURCE_READ("sourceReadMs"),
		EXTRACTION("extractionMs"),
		RELATIONSHIP_RESOLUTION("relationshipResolutionMs"),
		SCOPE_BUILD("scopeBuildMs"),
		PLANNING("planningMs"),
		IDENTITY_RESOLUTION("identityResolutionMs"),
		MAPPING("mappingMs"),
		VALIDATION("validationMs"),
		TARGET_LOAD("targetLoadMs"),
		RECONCILIATION("reconciliationMs");

		private final String jsonName;

		Stage(String jsonName) {
			this.jsonName = jsonName;
		}

		public String jsonName() {
			return jsonName;
		}
	}
}
