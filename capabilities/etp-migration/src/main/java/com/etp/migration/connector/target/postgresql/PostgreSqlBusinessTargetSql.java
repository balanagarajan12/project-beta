package com.etp.migration.connector.target.postgresql;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

final class PostgreSqlBusinessTargetSql {

	private PostgreSqlBusinessTargetSql() {
	}

	static String expectedCount(PostgreSqlStagingTarget.Resolved staging, int batchCount) {
		return "SELECT COUNT(*) FROM " + PostgreSqlStagingTarget.quoted(staging)
				+ " s WHERE " + runAndBatches(batchCount);
	}

	static String stagingDuplicateIdentities(
			PostgreSqlStagingTarget.Resolved staging,
			int batchCount,
			List<String> payloadKeys) {
		String groupBy = payloadSelect(payloadKeys);
		String groupPositions = groupPositions(payloadKeys.size());
		return "SELECT COUNT(*) FROM ("
				+ "SELECT " + groupBy
				+ " FROM " + PostgreSqlStagingTarget.quoted(staging) + " s"
				+ " WHERE " + runAndBatches(batchCount)
				+ " GROUP BY " + groupPositions
				+ " HAVING COUNT(*) > 1) duplicates";
	}

	static String matchedCount(
			PostgreSqlStagingTarget.Resolved staging,
			PromotionTargetConfiguration promotion,
			int batchCount,
			List<String> businessColumns) {
		return "SELECT COUNT(*) FROM " + PostgreSqlStagingTarget.quoted(staging) + " s"
				+ " WHERE " + runAndBatches(batchCount)
				+ " AND EXISTS (SELECT 1 FROM " + businessTable(promotion) + " b WHERE "
				+ joinPredicate(businessColumns) + ")";
	}

	static String ambiguousBusinessMatches(
			PostgreSqlStagingTarget.Resolved staging,
			PromotionTargetConfiguration promotion,
			int batchCount,
			List<String> businessColumns) {
		return "SELECT COUNT(*) FROM " + PostgreSqlStagingTarget.quoted(staging) + " s"
				+ " WHERE " + runAndBatches(batchCount)
				+ " AND (SELECT COUNT(*) FROM " + businessTable(promotion) + " b WHERE "
				+ joinPredicate(businessColumns) + ") > 1";
	}

	static String valueMismatchCount(
			PostgreSqlStagingTarget.Resolved staging,
			PromotionTargetConfiguration promotion,
			int batchCount,
			List<String> conflictColumns,
			List<Map.Entry<String, String>> valueColumns) {
		StringBuilder sql = new StringBuilder();
		sql.append("SELECT COUNT(*) FROM ").append(PostgreSqlStagingTarget.quoted(staging)).append(" s");
		sql.append(" JOIN ").append(businessTable(promotion)).append(" b ON ").append(joinPredicate(conflictColumns));
		sql.append(" WHERE ").append(runAndBatches(batchCount));
		if (!valueColumns.isEmpty()) {
			sql.append(" AND (");
			sql.append(valueColumns.stream()
					.map(column -> "b." + PostgreSqlIdentifiers.quote(column.getValue())
							+ "::text IS DISTINCT FROM " + payloadText())
					.collect(Collectors.joining(" OR ")));
			sql.append(")");
		} else {
			sql.append(" AND false");
		}
		return sql.toString();
	}

	static String jsonKeyForColumn(PromotionTargetConfiguration promotion, String businessColumn) {
		return promotion.columns().entrySet().stream()
				.filter(entry -> businessColumn.equals(entry.getValue()))
				.map(Map.Entry::getKey)
				.findFirst()
				.orElseThrow(() -> new IllegalArgumentException("Promotion conflict key is not mapped"));
	}

	private static String businessTable(PromotionTargetConfiguration promotion) {
		return PostgreSqlIdentifiers.quote(promotion.schema()) + "." + PostgreSqlIdentifiers.quote(promotion.table());
	}

	private static String runAndBatches(int batchCount) {
		return "s.migration_run_id = ? AND s.migration_batch_id IN (" + placeholders(batchCount) + ")";
	}

	private static String placeholders(int count) {
		List<String> marks = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			marks.add("?");
		}
		return String.join(", ", marks);
	}

	private static String joinPredicate(List<String> businessColumns) {
		return businessColumns.stream()
				.map(column -> "b." + PostgreSqlIdentifiers.quote(column)
						+ "::text IS NOT DISTINCT FROM " + payloadText())
				.collect(Collectors.joining(" AND "));
	}

	private static String payloadSelect(List<String> payloadKeys) {
		List<String> expressions = new ArrayList<>();
		for (int i = 0; i < payloadKeys.size(); i++) {
			expressions.add(payloadText());
		}
		return String.join(", ", expressions);
	}

	private static String payloadText() {
		return "jsonb_extract_path_text(s.target_payload, ?)";
	}

	private static String groupPositions(int count) {
		List<String> positions = new ArrayList<>();
		for (int i = 1; i <= count; i++) {
			positions.add(Integer.toString(i));
		}
		return String.join(", ", positions);
	}
}
