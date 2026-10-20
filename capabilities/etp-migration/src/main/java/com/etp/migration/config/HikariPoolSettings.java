package com.etp.migration.config;

import com.zaxxer.hikari.HikariConfig;

/**
 * Rejects pool timeouts that Hikari would otherwise silently replace with its own defaults or disable, and an
 * unbounded connection wait.
 */
public final class HikariPoolSettings {

	private static final long MIN_TIMEOUT_MS = 250;
	private static final long MAX_CONNECTION_TIMEOUT_MS = 300_000;
	private static final long MIN_LIFETIME_MS = 30_000;
	private static final long MIN_IDLE_TIMEOUT_MS = 10_000;

	private HikariPoolSettings() {
	}

	public static void validate(String prefix, HikariConfig config) {
		long connectionTimeout = config.getConnectionTimeout();
		long validationTimeout = config.getValidationTimeout();
		long maxLifetime = config.getMaxLifetime();
		long keepaliveTime = config.getKeepaliveTime();
		long idleTimeout = config.getIdleTimeout();
		require(connectionTimeout >= MIN_TIMEOUT_MS && connectionTimeout <= MAX_CONNECTION_TIMEOUT_MS,
				prefix + ".connection-timeout must be between 250ms and 5m; 0 would wait indefinitely for a connection");
		require(validationTimeout >= MIN_TIMEOUT_MS && validationTimeout < connectionTimeout,
				prefix + ".validation-timeout must be at least 250ms and shorter than connection-timeout");
		require(maxLifetime == 0 || maxLifetime >= MIN_LIFETIME_MS,
				prefix + ".max-lifetime must be 0 (no limit) or at least 30s");
		require(keepaliveTime == 0
						|| (keepaliveTime >= MIN_LIFETIME_MS && (maxLifetime == 0 || keepaliveTime < maxLifetime)),
				prefix + ".keepalive-time must be 0 (disabled) or at least 30s and shorter than max-lifetime");
		require(idleTimeout == 0
						|| (idleTimeout >= MIN_IDLE_TIMEOUT_MS && (maxLifetime == 0 || idleTimeout + 1_000 <= maxLifetime)),
				prefix + ".idle-timeout must be 0 (disabled) or at least 10s and at least 1s shorter than max-lifetime");
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new IllegalStateException(message);
		}
	}
}
