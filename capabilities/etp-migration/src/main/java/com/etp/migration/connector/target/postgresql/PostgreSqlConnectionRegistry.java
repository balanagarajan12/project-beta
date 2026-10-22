package com.etp.migration.connector.target.postgresql;

import com.etp.migration.config.HikariPoolSettings;
import com.etp.migration.connector.target.TargetInfrastructureException;
import com.etp.migration.processing.MigrationErrorCodes;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Resolves {@code target.connectionRef} and {@code target.stagingConnectionRef} to pooled connections.
 * A blank reference uses the default PostgreSQL target datasource. Named pools are created once and reused.
 * One migration has one business-target connection, so foreign keys stay inside that database.
 */
@Component
public class PostgreSqlConnectionRegistry implements DisposableBean {

	private static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");

	private final DataSource defaultDataSource;
	private final PostgreSqlConnectionProperties profiles;
	private final PostgreSqlTargetProperties defaults;
	private final ConcurrentHashMap<String, HikariDataSource> pools = new ConcurrentHashMap<>();

	@Autowired
	public PostgreSqlConnectionRegistry(
			PostgreSqlConnectionProperties profiles,
			PostgreSqlTargetProperties defaults,
			@Autowired(required = false) @Qualifier(PostgreSqlTargetClientConfig.DATASOURCE_BEAN) DataSource defaultDataSource) {
		this.profiles = profiles;
		this.defaults = defaults;
		this.defaultDataSource = defaultDataSource;
	}

	public static DataSource stagingOf(
			PostgreSqlConnectionRegistry registry,
			DataSource fallback,
			Map<String, Object> configuration) {
		if (registry != null) {
			return registry.staging(configuration);
		}
		return required(fallback);
	}

	public static DataSource targetOf(
			PostgreSqlConnectionRegistry registry,
			DataSource fallback,
			Map<String, Object> configuration) {
		if (registry != null) {
			return registry.target(configuration);
		}
		return required(fallback);
	}

	public DataSource staging(Map<String, Object> configuration) {
		String stagingRef = selectedReference(configuration, true);
		if (stagingRef == null) {
			return required(defaultDataSource);
		}
		return open(stagingRef);
	}

	public DataSource target(Map<String, Object> configuration) {
		String targetRef = selectedReference(configuration, false);
		if (targetRef == null) {
			return required(defaultDataSource);
		}
		return open(targetRef);
	}

	/**
	 * JDBC URL for the profile LOAD or promotion would open. Does not create a pool.
	 * A configured reference with no profile throws; it is not replaced by the other connection.
	 */
	String jdbcUrl(Map<String, Object> configuration, boolean staging) {
		String ref = selectedReference(configuration, staging);
		if (ref == null) {
			return null;
		}
		return requireProfile(ref).getUrl().trim();
	}

	private static String selectedReference(Map<String, Object> configuration, boolean staging) {
		if (staging) {
			String stagingRef = reference(configuration, "stagingConnectionRef");
			if (stagingRef != null) {
				return stagingRef;
			}
		}
		return reference(configuration, "connectionRef");
	}

	private DataSource open(String reference) {
		PostgreSqlConnectionProperties.Profile profile = requireProfile(reference);
		HikariDataSource existing = pools.get(reference);
		if (existing != null) {
			return existing;
		}
		return pools.computeIfAbsent(reference, ignored -> create(reference, profile));
	}

	private HikariDataSource create(String reference, PostgreSqlConnectionProperties.Profile profile) {
		HikariConfig config = new HikariConfig();
		config.setPoolName("etp-migration-pg-" + reference);
		config.setJdbcUrl(profile.getUrl().trim());
		config.setUsername(profile.getUsername());
		config.setPassword(profile.getPassword());
		int size = profile.getMaxPoolSize() == null ? 0 : profile.getMaxPoolSize();
		if (size < 1) {
			size = defaults.maxPoolSize() == null || defaults.maxPoolSize() < 1 ? 4 : defaults.maxPoolSize();
		}
		config.setMaximumPoolSize(size);
		config.setDriverClassName("org.postgresql.Driver");
		config.addDataSourceProperty("reWriteBatchedInserts", "true");
		PostgreSqlTargetProperties.Pool pool = defaults.pool();
		config.setConnectionTimeout(pool.connectionTimeout().toMillis());
		config.setValidationTimeout(pool.validationTimeout().toMillis());
		config.setIdleTimeout(pool.idleTimeout().toMillis());
		config.setMaxLifetime(pool.maxLifetime().toMillis());
		config.setKeepaliveTime(pool.keepaliveTime().toMillis());
		HikariPoolSettings.validate("etp.migration.postgresql.connections." + reference, config);
		return new HikariDataSource(config);
	}

	private PostgreSqlConnectionProperties.Profile requireProfile(String reference) {
		if (!REFERENCE.matcher(reference).matches()) {
			throw configurationError("Target connection reference is invalid");
		}
		PostgreSqlConnectionProperties.Profile profile = profiles.getConnections().get(reference);
		if (profile == null || profile.getUrl() == null || profile.getUrl().isBlank()) {
			throw configurationError("Target connection '" + reference + "' is not configured");
		}
		return profile;
	}

	@SuppressWarnings("unchecked")
	private static String reference(Map<String, Object> configuration, String name) {
		if (configuration == null || !(configuration.get("target") instanceof Map<?, ?> target)) {
			return null;
		}
		Object value = ((Map<String, Object>) target).get(name);
		if (value == null || value.toString().isBlank()) {
			return null;
		}
		return value.toString().trim();
	}

	private static DataSource required(DataSource dataSource) {
		if (dataSource == null) {
			throw configurationError("PostgreSQL target datasource is not configured");
		}
		return dataSource;
	}

	private static TargetInfrastructureException configurationError(String message) {
		return new TargetInfrastructureException(MigrationErrorCodes.TARGET_DATABASE_CONFIGURATION_ERROR, message);
	}

	@Override
	public void destroy() {
		pools.values().forEach(HikariDataSource::close);
		pools.clear();
	}
}
