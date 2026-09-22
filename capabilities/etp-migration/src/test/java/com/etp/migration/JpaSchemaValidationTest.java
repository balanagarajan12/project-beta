package com.etp.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.etp.migration.repository.MigrationArtifactRepository;
import com.etp.migration.repository.MigrationBatchRepository;
import com.etp.migration.repository.MigrationDefinitionRepository;
import com.etp.migration.repository.MigrationErrorRepository;
import com.etp.migration.repository.MigrationMappingRepository;
import com.etp.migration.repository.MigrationMappingRuleRepository;
import com.etp.migration.repository.MigrationPromotionBatchRepository;
import com.etp.migration.repository.MigrationPromotionRepository;
import com.etp.migration.repository.MigrationRecordRepository;
import com.etp.migration.repository.MigrationReconciliationRepository;
import com.etp.migration.repository.MigrationRunRepository;
import com.etp.migration.repository.MigrationSourceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
class JpaSchemaValidationTest {

	@Autowired
	private MigrationDefinitionRepository migrationDefinitionRepository;

	@Autowired
	private MigrationSourceRepository migrationSourceRepository;

	@Autowired
	private MigrationMappingRepository migrationMappingRepository;

	@Autowired
	private MigrationMappingRuleRepository migrationMappingRuleRepository;

	@Autowired
	private MigrationRunRepository migrationRunRepository;

	@Autowired
	private MigrationBatchRepository migrationBatchRepository;

	@Autowired
	private MigrationArtifactRepository migrationArtifactRepository;

	@Autowired
	private MigrationErrorRepository migrationErrorRepository;

	@Autowired
	private MigrationReconciliationRepository migrationReconciliationRepository;

	@Autowired
	private MigrationRecordRepository migrationRecordRepository;

	@Autowired
	private MigrationPromotionRepository migrationPromotionRepository;

	@Autowired
	private MigrationPromotionBatchRepository migrationPromotionBatchRepository;

	@Test
	void hibernateSchemaValidationSucceeds() {
	}

	@Test
	void repositoryBeansAreCreated() {
		assertThat(migrationDefinitionRepository).isNotNull();
		assertThat(migrationSourceRepository).isNotNull();
		assertThat(migrationMappingRepository).isNotNull();
		assertThat(migrationMappingRuleRepository).isNotNull();
		assertThat(migrationRunRepository).isNotNull();
		assertThat(migrationBatchRepository).isNotNull();
		assertThat(migrationArtifactRepository).isNotNull();
		assertThat(migrationErrorRepository).isNotNull();
		assertThat(migrationReconciliationRepository).isNotNull();
		assertThat(migrationRecordRepository).isNotNull();
		assertThat(migrationPromotionRepository).isNotNull();
		assertThat(migrationPromotionBatchRepository).isNotNull();
	}
}
