package com.etp.migration.mapping;

/**
 * Logical entity inside a migration mapping. Names are configuration, not known business types.
 * {@code targetEntity} is the configured target identifier and is not assumed to be a physical table.
 */
public record EntityDefinition(
		String name,
		String targetEntity,
		String sourceKey,
		String targetKey,
		TargetIdentitySpec targetIdentity
) {
	public EntityDefinition(String name, String targetEntity, String sourceKey, String targetKey) {
		this(name, targetEntity, sourceKey, targetKey, null);
	}
}
