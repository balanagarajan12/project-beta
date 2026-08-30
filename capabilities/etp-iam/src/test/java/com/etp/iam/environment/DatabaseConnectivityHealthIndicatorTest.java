package com.etp.iam.environment;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.health.contributor.Health;

class DatabaseConnectivityHealthIndicatorTest {

	@Test
	void healthIsUpWhenConnectionIsValid() throws Exception {
		DataSource dataSource = Mockito.mock(DataSource.class);
		Connection connection = Mockito.mock(Connection.class);
		Mockito.when(dataSource.getConnection()).thenReturn(connection);
		Mockito.when(connection.isValid(2)).thenReturn(true);

		DatabaseConnectivityHealthIndicator indicator = new DatabaseConnectivityHealthIndicator(dataSource);
		Health health = indicator.health();

		assertThat(health.getStatus().getCode()).isEqualTo("UP");
	}

	@Test
	void healthIsDownWhenConnectionIsInvalid() throws Exception {
		DataSource dataSource = Mockito.mock(DataSource.class);
		Connection connection = Mockito.mock(Connection.class);
		Mockito.when(dataSource.getConnection()).thenReturn(connection);
		Mockito.when(connection.isValid(2)).thenReturn(false);

		DatabaseConnectivityHealthIndicator indicator = new DatabaseConnectivityHealthIndicator(dataSource);
		Health health = indicator.health();

		assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
	}
}
