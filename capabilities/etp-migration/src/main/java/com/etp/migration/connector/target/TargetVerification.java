package com.etp.migration.connector.target;

import java.util.Map;

public record TargetVerification(
		boolean available,
		Long targetCount,
		String verificationScope,
		boolean businessTargetVerified,
		String reason,
		Map<String, Object> attributes
) {
	public static TargetVerification unavailable(String verificationScope, String reason) {
		return new TargetVerification(false, null, verificationScope, false, reason, Map.of());
	}
}
