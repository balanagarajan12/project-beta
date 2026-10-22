package com.etp.migration.connector.target.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PostgreSqlStagingIndexesTest {

	@Test
	void batchLookupIndexNameFitsPostgreSql() {
		assertThat(PostgreSqlStagingIndexes.indexName("uam_entity_stage")).isEqualTo("idx_uam_entity_stage_run_batch");
		assertThat(PostgreSqlStagingIndexes.indexName("x".repeat(80))).hasSize(63);
	}
}
