package com.etp.migration.support;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

final class SharedPostgreSqlTestcontainer {

	private static final PostgreSQLContainer<?> INSTANCE = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
			.withDatabaseName("etp_migration_test")
			.withUsername("etp_admin")
			.withPassword("etp_migration_test")
			.withCommand("postgres", "-c", "max_connections=300");

	private SharedPostgreSqlTestcontainer() {
	}

	static PostgreSQLContainer<?> instance() {
		if (!INSTANCE.isRunning()) {
			INSTANCE.start();
		}
		return INSTANCE;
	}
}
