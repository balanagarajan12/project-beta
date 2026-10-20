package com.etp.migration.support;

import java.util.List;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;
import org.springframework.test.context.TestContextAnnotationUtils;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.testcontainers.containers.PostgreSQLContainer;

public final class PostgreSqlTestcontainersContextCustomizerFactory implements ContextCustomizerFactory {

	@Override
	public ContextCustomizer createContextCustomizer(
			Class<?> testClass,
			List<ContextConfigurationAttributes> configAttributes) {
		if (TestContextAnnotationUtils.hasAnnotation(testClass, SpringBootTest.class)
				|| TestContextAnnotationUtils.hasAnnotation(testClass, DataJpaTest.class)) {
			return IsolatedPostgreSqlContextCustomizer.INSTANCE;
		}
		return null;
	}

	private static final class IsolatedPostgreSqlContextCustomizer implements ContextCustomizer {

		private static final IsolatedPostgreSqlContextCustomizer INSTANCE = new IsolatedPostgreSqlContextCustomizer();

		@Override
		public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration mergedConfig) {
			PostgreSQLContainer<?> postgres = SharedPostgreSqlTestcontainer.instance();
			TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
					context,
					"spring.datasource.url=" + postgres.getJdbcUrl(),
					"spring.datasource.username=" + postgres.getUsername(),
					"spring.datasource.password=" + postgres.getPassword(),
					"spring.datasource.driver-class-name=org.postgresql.Driver",
					"etp.migration.target.postgresql.url=" + postgres.getJdbcUrl(),
					"etp.migration.target.postgresql.username=" + postgres.getUsername(),
					"etp.migration.target.postgresql.password=" + postgres.getPassword());
		}

		@Override
		public boolean equals(Object other) {
			return this == other || other instanceof IsolatedPostgreSqlContextCustomizer;
		}

		@Override
		public int hashCode() {
			return IsolatedPostgreSqlContextCustomizer.class.hashCode();
		}
	}
}
