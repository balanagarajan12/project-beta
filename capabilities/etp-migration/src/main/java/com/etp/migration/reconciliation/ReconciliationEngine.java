package com.etp.migration.reconciliation;

import com.etp.migration.connector.target.TargetVerification;
import com.etp.migration.entity.MigrationRun;
import com.etp.migration.entity.MigrationRunStatus;
import com.etp.migration.entity.ReconciliationStatus;
import com.etp.migration.entity.ReconciliationType;
import com.etp.migration.processing.MigrationErrorCodes;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ReconciliationEngine {

	public ReconciliationComputation count(ReconciliationContext context) {
		TargetVerification target = context.targetVerification();
		boolean internallyConsistent = context.processedCount() == context.accountedCount()
				&& context.processedCount() == context.sourceCount();
		boolean stagingScope = !"BUSINESS_TARGET".equals(target.verificationScope());
		boolean targetMismatch = stagingScope
				&& target.available()
				&& target.targetCount() != null
				&& target.targetCount() != context.successCount();
		long difference = stagingScope && target.available() && target.targetCount() != null
				? Math.abs(context.successCount() - target.targetCount())
				: Math.abs(context.sourceCount() - context.processedCount());
		ReconciliationStatus status;
		String errorCode = null;
		if (!internallyConsistent || targetMismatch) {
			status = ReconciliationStatus.FAILED;
			errorCode = targetMismatch
					? MigrationErrorCodes.TARGET_COUNT_MISMATCH
					: MigrationErrorCodes.RECONCILIATION_FAILED;
		} else if (!target.available() || hasProcessingGaps(context)) {
			status = ReconciliationStatus.PASSED_WITH_WARNINGS;
			if (!target.available()) {
				errorCode = MigrationErrorCodes.TARGET_VERIFICATION_UNAVAILABLE;
			}
		} else {
			status = ReconciliationStatus.PASSED;
		}
		Map<String, Object> details = baseCounts(context, target, difference, internallyConsistent);
		if (errorCode != null) {
			details.put("errorCode", errorCode);
		}
		if (targetMismatch) {
			details.put("reason", "Target count does not match successful load count");
		}
		return new ReconciliationComputation(
				ReconciliationType.COUNT,
				status,
				context.sourceCount(),
				context.processedCount(),
				context.successCount(),
				context.rejectedCount(),
				context.duplicateCount(),
				context.failureCount(),
				difference,
				details);
	}

	public ReconciliationComputation checksum(ReconciliationContext context) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("sourceChecksum", context.sourceChecksum());
		details.put("checksumAlgorithm", context.checksumAlgorithm());
		details.put("targetChecksum", null);
		details.put("comparisonPerformed", false);
		details.put("reason", "Source and target representations are not canonical-equivalent");
		return new ReconciliationComputation(
				ReconciliationType.CHECKSUM,
				ReconciliationStatus.PASSED_WITH_WARNINGS,
				context.sourceCount(),
				context.processedCount(),
				context.successCount(),
				context.rejectedCount(),
				context.duplicateCount(),
				context.failureCount(),
				0L,
				details);
	}

	public ReconciliationComputation target(ReconciliationContext context) {
		TargetVerification target = context.targetVerification();
		boolean businessTarget = "BUSINESS_TARGET".equals(target.verificationScope());
		long expected = expectedCount(context, target);
		long observed = target.targetCount() == null ? 0L : target.targetCount();
		long difference = target.available() ? Math.abs(expected - observed) : 0L;
		ReconciliationStatus status;
		String errorCode = null;
		if (!target.available()) {
			status = ReconciliationStatus.PASSED_WITH_WARNINGS;
			errorCode = attributeErrorCode(target);
			if (errorCode == null) {
				errorCode = MigrationErrorCodes.TARGET_VERIFICATION_UNAVAILABLE;
			}
		} else if (booleanAttribute(target, "duplicateIdentity")) {
			status = ReconciliationStatus.FAILED;
			errorCode = MigrationErrorCodes.TARGET_IDENTITY_AMBIGUOUS;
		} else if (booleanAttribute(target, "valueMismatch")) {
			status = ReconciliationStatus.FAILED;
			errorCode = MigrationErrorCodes.TARGET_VALUE_MISMATCH;
		} else if (target.targetCount() == null || observed != expected) {
			status = ReconciliationStatus.FAILED;
			errorCode = MigrationErrorCodes.TARGET_COUNT_MISMATCH;
		} else if (!businessTarget && hasProcessingGaps(context)) {
			status = ReconciliationStatus.PASSED_WITH_WARNINGS;
		} else {
			status = ReconciliationStatus.PASSED;
		}
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("verificationScope", target.verificationScope());
		details.put("businessTargetVerified", target.businessTargetVerified());
		details.put("targetVerificationAvailable", target.available());
		details.put("targetCount", target.targetCount());
		details.put("successCount", context.successCount());
		details.put("expectedCount", expected);
		details.put("difference", difference);
		if (target.reason() != null) {
			details.put("reason", target.reason());
		}
		if (target.attributes() != null) {
			details.putAll(target.attributes());
		}
		if (errorCode != null) {
			details.put("errorCode", errorCode);
		}
		return new ReconciliationComputation(
				ReconciliationType.TARGET,
				status,
				businessTarget ? expected : context.sourceCount(),
				context.processedCount(),
				context.successCount(),
				context.rejectedCount(),
				context.duplicateCount(),
				context.failureCount(),
				difference,
				details);
	}

	public ReconciliationComputation full(
			ReconciliationContext context,
			ReconciliationComputation count,
			ReconciliationComputation checksum,
			ReconciliationComputation target) {
		ReconciliationStatus status = aggregate(count.status(), checksum.status(), target.status());
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("countStatus", count.status().name());
		details.put("checksumStatus", checksum.status().name());
		details.put("targetStatus", target.status().name());
		details.put("runStatus", context.run().getStatus().name());
		details.put("runStatusIndependent", true);
		if (count.details().get("errorCode") != null) {
			details.put("errorCode", count.details().get("errorCode"));
		} else if (target.details().get("errorCode") != null) {
			details.put("errorCode", target.details().get("errorCode"));
		}
		return new ReconciliationComputation(
				ReconciliationType.FULL,
				status,
				context.sourceCount(),
				context.processedCount(),
				context.successCount(),
				context.rejectedCount(),
				context.duplicateCount(),
				context.failureCount(),
				count.differenceCount(),
				details);
	}

	private static Map<String, Object> baseCounts(
			ReconciliationContext context,
			TargetVerification target,
			long difference,
			boolean internallyConsistent) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("verificationScope", target.verificationScope());
		details.put("businessTargetVerified", target.businessTargetVerified());
		details.put("targetVerificationAvailable", target.available());
		details.put("sourceCount", context.sourceCount());
		details.put("processedCount", context.processedCount());
		details.put("successCount", context.successCount());
		details.put("failureCount", context.failureCount());
		details.put("rejectedCount", context.rejectedCount());
		details.put("duplicateCount", context.duplicateCount());
		details.put("targetCount", target.targetCount());
		details.put("difference", difference);
		details.put("accountedCount", context.accountedCount());
		details.put("internallyConsistent", internallyConsistent);
		details.put("runStatus", context.run().getStatus().name());
		if (target.attributes() != null) {
			details.putAll(target.attributes());
		}
		if (target.reason() != null && !target.available()) {
			details.put("reason", target.reason());
		}
		return details;
	}

	private static boolean hasProcessingGaps(ReconciliationContext context) {
		return context.failureCount() > 0
				|| context.rejectedCount() > 0
				|| context.run().getStatus() == MigrationRunStatus.COMPLETED_WITH_ERRORS
				|| context.run().getStatus() == MigrationRunStatus.FAILED;
	}

	static ReconciliationStatus aggregate(ReconciliationStatus... statuses) {
		ReconciliationStatus worst = ReconciliationStatus.PASSED;
		for (ReconciliationStatus status : statuses) {
			if (status == ReconciliationStatus.FAILED) {
				return ReconciliationStatus.FAILED;
			}
			if (status == ReconciliationStatus.PASSED_WITH_WARNINGS) {
				worst = ReconciliationStatus.PASSED_WITH_WARNINGS;
			}
		}
		return worst;
	}

	private static long expectedCount(ReconciliationContext context, TargetVerification target) {
		if (target.attributes() != null && target.attributes().get("expectedCount") instanceof Number number) {
			return number.longValue();
		}
		return context.successCount();
	}

	private static boolean booleanAttribute(TargetVerification target, String key) {
		return target.attributes() != null && Boolean.TRUE.equals(target.attributes().get(key));
	}

	private static String attributeErrorCode(TargetVerification target) {
		if (target.attributes() == null || target.attributes().get("errorCode") == null) {
			return null;
		}
		return target.attributes().get("errorCode").toString();
	}
}
