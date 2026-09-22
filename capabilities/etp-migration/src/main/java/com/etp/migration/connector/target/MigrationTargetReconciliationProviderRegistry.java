package com.etp.migration.connector.target;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class MigrationTargetReconciliationProviderRegistry {

	private final Map<String, MigrationTargetReconciliationProvider> providers;

	public MigrationTargetReconciliationProviderRegistry(List<MigrationTargetReconciliationProvider> providers) {
		this.providers = providers.stream()
				.collect(Collectors.toMap(provider -> provider.targetType().toUpperCase(Locale.ROOT), Function.identity()));
	}

	public Optional<MigrationTargetReconciliationProvider> find(String targetType) {
		if (targetType == null || targetType.isBlank()) {
			return Optional.empty();
		}
		return Optional.ofNullable(providers.get(targetType.trim().toUpperCase(Locale.ROOT)));
	}
}
