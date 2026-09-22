package com.etp.migration.connector.target.postgresql;

import com.etp.migration.entity.PromotionStrategy;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

final class PostgreSqlPromotionSql {

	private PostgreSqlPromotionSql() {
	}

	static String writeStatement(PromotionTargetConfiguration configuration) {
		List<String> columns = columnNames(configuration);
		String table = PostgreSqlIdentifiers.quote(configuration.schema())
				+ "."
				+ PostgreSqlIdentifiers.quote(configuration.table());
		String columnList = columns.stream().map(PostgreSqlIdentifiers::quote).collect(Collectors.joining(", "));
		String placeholders = columns.stream().map(column -> "?").collect(Collectors.joining(", "));
		String insert = "INSERT INTO " + table + " (" + columnList + ") VALUES (" + placeholders + ")";
		if (configuration.strategy() != PromotionStrategy.UPSERT) {
			return insert;
		}
		String conflict = configuration.conflictKeys().stream()
				.map(PostgreSqlIdentifiers::quote)
				.collect(Collectors.joining(", "));
		List<String> updates = columns.stream()
				.filter(column -> !configuration.conflictKeys().contains(column))
				.toList();
		if (updates.isEmpty()) {
			return insert + " ON CONFLICT (" + conflict + ") DO NOTHING";
		}
		String setClause = updates.stream()
				.map(column -> PostgreSqlIdentifiers.quote(column) + " = EXCLUDED." + PostgreSqlIdentifiers.quote(column))
				.collect(Collectors.joining(", "));
		return insert + " ON CONFLICT (" + conflict + ") DO UPDATE SET " + setClause;
	}

	static List<String> jsonKeys(PromotionTargetConfiguration configuration) {
		return new ArrayList<>(configuration.columns().keySet());
	}

	static List<String> columnNames(PromotionTargetConfiguration configuration) {
		return new ArrayList<>(configuration.columns().values());
	}
}
