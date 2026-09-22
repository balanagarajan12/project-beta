package com.etp.migration.connector.target.uam;

import com.etp.migration.connector.target.MigrationTargetReconciliationProvider;
import com.etp.migration.connector.target.TargetVerification;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class UamTargetReconciliationProvider implements MigrationTargetReconciliationProvider {

	@Override
	public String targetType() {
		return UamTargetConnector.TARGET_TYPE;
	}

	@Override
	public TargetVerification verify(UUID migrationRunId, Map<String, Object> definitionConfiguration) {
		return TargetVerification.unavailable(
				"BUSINESS_TARGET",
				"UAM target count verification is not available in the current target contract");
	}
}
