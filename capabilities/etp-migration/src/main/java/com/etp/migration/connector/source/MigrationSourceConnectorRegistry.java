package com.etp.migration.connector.source;

import com.etp.migration.entity.MigrationSource;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class MigrationSourceConnectorRegistry {

	private final List<MigrationSourceConnector> connectors;

	public MigrationSourceConnectorRegistry(List<MigrationSourceConnector> connectors) {
		this.connectors = connectors;
	}

	public MigrationSourceConnector require(MigrationSource source) {
		if (source == null || source.getSourceType() == null || source.getSourceType().isBlank()) {
			throw new InvalidSourceConfigurationException("Source type is required");
		}
		return connectors.stream()
				.filter(connector -> connector.supports(source))
				.findFirst()
				.orElseGet(() -> {
					com.etp.migration.connector.source.csv.CsvFileSourceConfiguration.from(source.getConfiguration());
					throw new UnsupportedSourceTypeException("No source connector is available for the configured source");
				});
	}
}
