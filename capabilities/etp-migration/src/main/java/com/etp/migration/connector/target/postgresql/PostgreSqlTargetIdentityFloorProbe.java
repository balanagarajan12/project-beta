package com.etp.migration.connector.target.postgresql;

import com.etp.migration.identity.TargetIdentityFloorProbe;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * One-time floor for a prefix. Matches only ids that are that prefix plus the configured digit length,
 * so a longer prefix is not treated as the shorter one.
 */
@Component
public class PostgreSqlTargetIdentityFloorProbe implements TargetIdentityFloorProbe {

	private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

	private final JdbcTemplate jdbc;

	@Autowired
	public PostgreSqlTargetIdentityFloorProbe(
			@Autowired(required = false) @Qualifier(PostgreSqlTargetClientConfig.DATASOURCE_BEAN) DataSource dataSource) {
		this.jdbc = dataSource == null ? null : new JdbcTemplate(dataSource);
	}

	@Override
	public long existingMaximum(String schema, String table, String idColumn, String prefix, int sequenceLength) {
		if (jdbc == null || schema == null || schema.isBlank() || table == null || table.isBlank()
				|| idColumn == null || idColumn.isBlank()) {
			return 0L;
		}
		if (!IDENTIFIER.matcher(schema).matches()
				|| !IDENTIFIER.matcher(table).matches()
				|| !IDENTIFIER.matcher(idColumn).matches()) {
			throw new IllegalArgumentException("Target identity floor identifiers are invalid");
		}
		if (prefix == null || !prefix.matches("[A-Za-z][A-Za-z0-9-]*") || sequenceLength < 1 || sequenceLength > 18) {
			throw new IllegalArgumentException("Target identity floor pattern is invalid");
		}
		String pattern = "^" + prefix + "[0-9]{" + sequenceLength + "}$";
		String sql = "SELECT MAX(CAST(SUBSTRING(" + PostgreSqlIdentifiers.quote(idColumn) + " FROM ?) AS BIGINT)) FROM "
				+ PostgreSqlIdentifiers.quote(schema) + "." + PostgreSqlIdentifiers.quote(table)
				+ " WHERE " + PostgreSqlIdentifiers.quote(idColumn) + " ~ ?";
		Long maximum = jdbc.query(sql, resultSet -> {
			if (!resultSet.next() || resultSet.getObject(1) == null) {
				return null;
			}
			return resultSet.getLong(1);
		}, prefix.length() + 1, pattern);
		return maximum == null ? 0L : maximum;
	}
}
