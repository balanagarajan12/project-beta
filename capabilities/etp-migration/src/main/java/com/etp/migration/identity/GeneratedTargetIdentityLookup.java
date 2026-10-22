package com.etp.migration.identity;

import com.etp.migration.mapping.EntityDefinition;
import com.etp.migration.mapping.EntityRelationshipMapping;
import com.etp.migration.mapping.TargetIdentitySpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fills source identities that an existing lookup did not find.
 * Values already returned by {@link TargetIdentityLookup} are kept.
 * Entities without {@code targetIdentity} stay on that lookup.
 */
public final class GeneratedTargetIdentityLookup implements TargetIdentityLookup {

	private final GeneratedTargetIdentityService service;
	private final UUID migrationRunId;
	private final EntityRelationshipMapping mapping;
	private final String schema;
	private final TargetIdentityLookup delegate;
	private final javax.sql.DataSource targetDataSource;

	private GeneratedTargetIdentityLookup(
			GeneratedTargetIdentityService service,
			UUID migrationRunId,
			EntityRelationshipMapping mapping,
			String schema,
			TargetIdentityLookup delegate,
			javax.sql.DataSource targetDataSource) {
		this.service = service;
		this.migrationRunId = migrationRunId;
		this.mapping = mapping;
		this.schema = schema;
		this.delegate = delegate;
		this.targetDataSource = targetDataSource;
	}

	public static TargetIdentityLookup decorate(
			GeneratedTargetIdentityService service,
			UUID migrationRunId,
			EntityRelationshipMapping mapping,
			Map<String, Object> targetConfiguration,
			TargetIdentityLookup delegate) {
		return decorate(service, migrationRunId, mapping, targetConfiguration, delegate, null);
	}

	public static TargetIdentityLookup decorate(
			GeneratedTargetIdentityService service,
			UUID migrationRunId,
			EntityRelationshipMapping mapping,
			Map<String, Object> targetConfiguration,
			TargetIdentityLookup delegate,
			javax.sql.DataSource targetDataSource) {
		if (!generates(mapping)) {
			return delegate == null ? (entityName, sourceIdentities) -> Map.of() : delegate;
		}
		if (service == null) {
			throw new IllegalStateException("Target identity generation is not available");
		}
		if (migrationRunId == null) {
			throw new IllegalArgumentException("migration run id is required");
		}
		TargetIdentityLookup fallback = delegate == null
				? (entityName, sourceIdentities) -> Map.of()
				: delegate;
		return new GeneratedTargetIdentityLookup(
				service, migrationRunId, mapping, schema(targetConfiguration), fallback, targetDataSource);
	}

	@Override
	public Map<String, String> lookup(String entityName, List<String> sourceIdentities) {
		Map<String, String> existing = delegate.lookup(entityName, sourceIdentities);
		if (existing == null) {
			existing = Map.of();
		}
		EntityDefinition definition = definition(entityName);
		TargetIdentitySpec spec = definition == null ? null : definition.targetIdentity();
		if (spec == null) {
			return existing;
		}
		List<String> missing = new ArrayList<>();
		if (sourceIdentities != null) {
			for (String sourceIdentity : sourceIdentities) {
				String found = sourceIdentity == null ? null : existing.get(sourceIdentity);
				if (found == null || found.isBlank()) {
					if (sourceIdentity != null && !sourceIdentity.isBlank()) {
						missing.add(sourceIdentity);
					}
				}
			}
		}
		Map<String, String> merged = new LinkedHashMap<>();
		if (!missing.isEmpty()) {
			merged.putAll(service.assign(
					migrationRunId,
					entityName,
					missing,
					spec,
					schema,
					definition.targetEntity(),
					definition.targetKey(),
					targetDataSource));
		}
		for (Map.Entry<String, String> entry : existing.entrySet()) {
			if (entry.getValue() != null && !entry.getValue().isBlank()) {
				merged.put(entry.getKey(), entry.getValue().trim());
			}
		}
		return merged;
	}

	private EntityDefinition definition(String entityName) {
		if (mapping == null || entityName == null) {
			return null;
		}
		for (EntityDefinition entity : mapping.entities()) {
			if (entityName.equals(entity.name())) {
				return entity;
			}
		}
		return null;
	}

	private static boolean generates(EntityRelationshipMapping mapping) {
		if (mapping == null) {
			return false;
		}
		for (EntityDefinition entity : mapping.entities()) {
			if (entity.targetIdentity() != null) {
				return true;
			}
		}
		return false;
	}

	private static String schema(Map<String, Object> configuration) {
		if (configuration == null || !(configuration.get("target") instanceof Map<?, ?> target)) {
			return null;
		}
		Object schema = target.get("schema");
		if (schema == null || schema.toString().isBlank()) {
			return null;
		}
		return schema.toString().trim();
	}
}
