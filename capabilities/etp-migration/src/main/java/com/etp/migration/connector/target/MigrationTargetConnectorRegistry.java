package com.etp.migration.connector.target;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class MigrationTargetConnectorRegistry {

	private final Map<String, MigrationTargetConnector> connectors;

	public MigrationTargetConnectorRegistry(List<MigrationTargetConnector> connectors) {
		this.connectors = connectors.stream()
				.collect(Collectors.toMap(connector -> connector.targetType().toUpperCase(Locale.ROOT), Function.identity()));
	}

	public MigrationTargetConnector require(String targetType) {
		if (targetType == null || targetType.isBlank()) {
			throw new UnsupportedTargetTypeException(targetType);
		}
		MigrationTargetConnector connector = connectors.get(targetType.trim().toUpperCase(Locale.ROOT));
		if (connector == null) {
			throw new UnsupportedTargetTypeException(targetType);
		}
		return connector;
	}
}
