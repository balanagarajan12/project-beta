package com.etp.migration.migration;

import com.etp.migration.connector.target.postgresql.PostgreSqlStagingConfiguration;
import com.etp.migration.connector.target.postgresql.PostgreSqlStagingConfiguration.StagingFieldViolation;
import com.etp.migration.entity.LoadMode;
import com.etp.migration.migration.dto.ApiFieldError;
import java.util.List;
import java.util.Map;

final class PostgreSqlBulkTargetRules {

	private PostgreSqlBulkTargetRules() {
	}

	static void require(
			String targetType,
			LoadMode loadMode,
			Map<String, Object> configuration,
			String fieldPrefix) {
		List<StagingFieldViolation> violations = PostgreSqlStagingConfiguration.violations(
				targetType, loadMode, configuration, fieldPrefix);
		if (violations.isEmpty()) {
			return;
		}
		throw new RequestValidationException(violations.stream()
				.map(violation -> new ApiFieldError(violation.field(), violation.rejectedValue(), violation.message(), null))
				.toList());
	}
}
