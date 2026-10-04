package com.etp.migration.identity;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;

/**
 * Reserves id ranges on the control-plane table {@code target_identity_allocator}.
 * The row lock makes two concurrent reservations receive different ranges.
 * Existing target data is read only while {@code floor_established} is false.
 */
@Service
public class PostgresTargetIdentityAllocator implements TargetIdentityAllocator {

	private final JdbcTemplate jdbc;

	public PostgresTargetIdentityAllocator(DataSource dataSource) {
		this.jdbc = new JdbcTemplate(dataSource);
	}

	@Override
	@Transactional
	public long reserve(String namespace, int count, IdentityFloor floor) {
		if (namespace == null || namespace.isBlank()) {
			throw new IllegalArgumentException("Identity namespace is required");
		}
		if (count < 1) {
			throw new IllegalArgumentException("Identity range count must be greater than 0");
		}
		jdbc.update(
				"""
				INSERT INTO target_identity_allocator (namespace, current_value, floor_established, updated_at)
				VALUES (?, 0, FALSE, CURRENT_TIMESTAMP)
				ON CONFLICT (namespace) DO NOTHING
				""",
				namespace);
		Map<String, Object> row = jdbc.queryForMap(
				"SELECT current_value, floor_established FROM target_identity_allocator WHERE namespace = ? FOR UPDATE",
				namespace);
		long current = ((Number) row.get("current_value")).longValue();
		boolean established = flag(row.get("floor_established"));
		if (!established) {
			long existing = floor == null ? 0L : Math.max(0L, floor.existingMaximum());
			if (existing > current) {
				current = existing;
			}
		}
		long start = current + 1;
		jdbc.update(
				"""
				UPDATE target_identity_allocator
				SET current_value = ?, floor_established = TRUE, updated_at = CURRENT_TIMESTAMP
				WHERE namespace = ?
				""",
				current + count,
				namespace);
		return start;
	}

	private static boolean flag(Object value) {
		if (value instanceof Boolean bool) {
			return bool;
		}
		if (value instanceof Number number) {
			return number.intValue() != 0;
		}
		return value != null && Boolean.parseBoolean(value.toString());
	}
}
