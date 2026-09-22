package com.etp.migration.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.etp.migration.connector.target.postgresql.PostgreSqlTargetClientConfig;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class IsolatedPostgreSqlDatasourceIntegrationTest {

	@Autowired
	private DataSource dataSource;

	@Autowired
	@Qualifier(PostgreSqlTargetClientConfig.DATASOURCE_BEAN)
	private DataSource targetDataSource;

	@Autowired
	private Environment environment;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	@Test
	void applicationAndTargetDatasourcesUseTestcontainersNotLocalDemo() throws Exception {
		assertIsolated(jdbcUrl(dataSource));
		assertIsolated(jdbcUrl(targetDataSource));
		assertIsolated(environment.getProperty("spring.datasource.url"));
		assertIsolated(environment.getProperty("etp.migration.target.postgresql.url"));
		assertThat(environment.getProperty("spring.datasource.url")).contains("etp_migration_test");
	}

	private static String jdbcUrl(DataSource dataSource) throws Exception {
		try (Connection connection = dataSource.getConnection()) {
			DatabaseMetaData metaData = connection.getMetaData();
			return metaData.getURL();
		}
	}

	private static void assertIsolated(String jdbcUrl) {
		assertThat(jdbcUrl)
				.isNotBlank()
				.doesNotContain("localhost:55432")
				.doesNotContain("localhost:55433")
				.doesNotContain("/etp_migration?");
	}
}
