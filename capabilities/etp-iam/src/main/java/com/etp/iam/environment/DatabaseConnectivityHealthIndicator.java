package com.etp.iam.environment;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

@Component
public class DatabaseConnectivityHealthIndicator implements HealthIndicator {

	private final DataSource dataSource;

	public DatabaseConnectivityHealthIndicator(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	@Override
	public Health health() {
		try (Connection connection = dataSource.getConnection()) {
			if (connection.isValid(2)) {
				return Health.up()
						.withDetail("database", "connectivity verified")
						.build();
			}
			return Health.down()
					.withDetail("database", "connection validation failed")
					.build();
		}
		catch (SQLException ex) {
			return Health.down(ex)
					.withDetail("database", "unable to obtain connection")
					.build();
		}
	}
}
