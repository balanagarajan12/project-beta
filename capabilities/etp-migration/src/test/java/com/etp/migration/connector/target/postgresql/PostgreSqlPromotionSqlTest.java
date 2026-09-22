package com.etp.migration.connector.target.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import com.etp.migration.entity.PromotionStrategy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PostgreSqlPromotionSqlTest {

	@Test
	void insertUsesBoundPlaceholdersAndQuotedIdentifiers() {
		PromotionTargetConfiguration configuration = configuration(
				PromotionStrategy.INSERT,
				List.of());

		assertThat(PostgreSqlPromotionSql.writeStatement(configuration)).isEqualTo(
				"INSERT INTO \"promo_app\".\"address\" (\"external_id\", \"address_line_1\", \"city\") VALUES (?, ?, ?)");
	}

	@Test
	void upsertUpdatesNonConflictColumnsOnly() {
		PromotionTargetConfiguration configuration = configuration(
				PromotionStrategy.UPSERT,
				List.of("external_id"));

		assertThat(PostgreSqlPromotionSql.writeStatement(configuration)).isEqualTo(
				"INSERT INTO \"promo_app\".\"address\" (\"external_id\", \"address_line_1\", \"city\") VALUES (?, ?, ?)"
						+ " ON CONFLICT (\"external_id\") DO UPDATE SET"
						+ " \"address_line_1\" = EXCLUDED.\"address_line_1\", \"city\" = EXCLUDED.\"city\"");
	}

	@Test
	void upsertWithOnlyConflictKeysUsesDoNothing() {
		Map<String, String> columns = new LinkedHashMap<>();
		columns.put("external_id", "external_id");
		PromotionTargetConfiguration configuration = new PromotionTargetConfiguration(
				true, "promo_app", "address", PromotionStrategy.UPSERT, columns, List.of("external_id"));

		assertThat(PostgreSqlPromotionSql.writeStatement(configuration)).isEqualTo(
				"INSERT INTO \"promo_app\".\"address\" (\"external_id\") VALUES (?) ON CONFLICT (\"external_id\") DO NOTHING");
	}

	@Test
	void upsertSupportsCompositeConflictKeys() {
		PromotionTargetConfiguration configuration = configuration(
				PromotionStrategy.UPSERT,
				List.of("external_id", "city"));

		assertThat(PostgreSqlPromotionSql.writeStatement(configuration)).contains(
				"ON CONFLICT (\"external_id\", \"city\") DO UPDATE SET \"address_line_1\" = EXCLUDED.\"address_line_1\"");
		assertThat(PostgreSqlPromotionSql.writeStatement(configuration)).doesNotContain(
				"\"external_id\" = EXCLUDED.\"external_id\"");
		assertThat(PostgreSqlPromotionSql.writeStatement(configuration)).doesNotContain(
				"\"city\" = EXCLUDED.\"city\"");
	}

	private static PromotionTargetConfiguration configuration(PromotionStrategy strategy, List<String> conflictKeys) {
		Map<String, String> columns = new LinkedHashMap<>();
		columns.put("external_id", "external_id");
		columns.put("address_line_1", "address_line_1");
		columns.put("city", "city");
		return new PromotionTargetConfiguration(true, "promo_app", "address", strategy, columns, conflictKeys);
	}
}
