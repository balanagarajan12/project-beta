package com.etp.migration.processing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Idempotency identity for one entity instance inside a migration run.
 * The logical key is {@code (migrationRunId, entityName, sourceIdentity)}.
 * Batch id is not part of the key: the same entity encountered in another batch
 * is the same logical entity, not a second target row.
 * The stored form is {@code entity-v1:} plus the hex SHA-256 of those three values
 * separated by the unit separator. Entity names and source identities are not concatenated raw,
 * so a delimiter inside either value cannot collide with another identity.
 */
public final class EntityIdempotencyKey {

	public static final String PREFIX = "entity-v1:";
	private static final char SEPARATOR = '\u001f';

	private EntityIdempotencyKey() {
	}

	public static String of(UUID migrationRunId, String entityName, String sourceIdentity) {
		if (migrationRunId == null) {
			throw new IllegalArgumentException("migrationRunId is required");
		}
		if (entityName == null || entityName.isBlank()) {
			throw new IllegalArgumentException("entityName is required");
		}
		if (sourceIdentity == null || sourceIdentity.isBlank()) {
			throw new IllegalArgumentException("sourceIdentity is required");
		}
		String material = migrationRunId.toString() + SEPARATOR + entityName.trim() + SEPARATOR + sourceIdentity.trim();
		return PREFIX + sha256(material);
	}

	public static boolean isEntityKey(String key) {
		return key != null && key.startsWith(PREFIX);
	}

	private static String sha256(String material) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(digest.length * 2);
			for (byte value : digest) {
				hex.append(Character.forDigit((value >> 4) & 0xF, 16));
				hex.append(Character.forDigit(value & 0xF, 16));
			}
			return hex.toString();
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is required", ex);
		}
	}
}
