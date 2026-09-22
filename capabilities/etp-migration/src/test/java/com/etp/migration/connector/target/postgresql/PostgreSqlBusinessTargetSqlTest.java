package com.etp.migration.connector.target.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import com.etp.migration.entity.PromotionStrategy;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PostgreSqlBusinessTargetSqlTest {

	@Test
	void duplicateIdentitySqlBindsEachJsonKeyOnce() {
		PostgreSqlStagingTarget.Resolved staging = new PostgreSqlStagingTarget.Resolved(
				"migration_staging", "address_load", "NONE");
		String sql = PostgreSqlBusinessTargetSql.stagingDuplicateIdentities(staging, 2, List.of("external_id"));
		assertThat(countPlaceholders(sql)).isEqualTo(1 + 2 + 1);
		assertThat(sql).contains("GROUP BY 1");
		assertThat(sql).contains("jsonb_extract_path_text(s.target_payload, ?)");
	}

	@Test
	void valueMismatchSqlQualifiesBusinessColumns() {
		PostgreSqlStagingTarget.Resolved staging = new PostgreSqlStagingTarget.Resolved(
				"migration_staging", "address_load", "NONE");
		PromotionTargetConfiguration promotion = new PromotionTargetConfiguration(
				true,
				"promo_app",
				"address",
				PromotionStrategy.UPSERT,
				Map.of("external_id", "external_id", "city", "city"),
				List.of("external_id"));
		String sql = PostgreSqlBusinessTargetSql.valueMismatchCount(
				staging,
				promotion,
				1,
				List.of("external_id"),
				List.of(Map.entry("city", "city")));
		assertThat(sql).contains("b.\"city\"::text IS DISTINCT FROM jsonb_extract_path_text(s.target_payload, ?)");
		assertThat(countPlaceholders(sql)).isEqualTo(1 + 1 + 1 + 1);
	}

	private static int countPlaceholders(String sql) {
		int count = 0;
		for (int i = 0; i < sql.length(); i++) {
			if (sql.charAt(i) == '?') {
				count++;
			}
		}
		return count;
	}
}
