package com.etp.migration.connector.target.postgresql;

import com.etp.migration.connector.target.TargetFailureDiagnostics;
import com.etp.migration.connector.target.TargetInfrastructureException;
import com.etp.migration.connector.target.TargetVerification;
import com.etp.migration.entity.MigrationPromotion;
import com.etp.migration.entity.MigrationPromotionBatch;
import com.etp.migration.entity.PromotionBatchStatus;
import com.etp.migration.entity.PromotionStatus;
import com.etp.migration.processing.MigrationErrorCodes;
import com.etp.migration.promotion.PromotionException;
import com.etp.migration.repository.MigrationPromotionBatchRepository;
import com.etp.migration.repository.MigrationPromotionRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class PostgreSqlBusinessTargetVerifier {

	private static final Set<PromotionStatus> EXECUTED = Set.of(
			PromotionStatus.PROMOTED,
			PromotionStatus.PARTIALLY_PROMOTED,
			PromotionStatus.PROMOTION_FAILED);

	private final PostgreSqlTargetProperties properties;
	private final DataSource dataSource;
	private final MigrationPromotionRepository promotionRepository;
	private final MigrationPromotionBatchRepository promotionBatchRepository;

	@Autowired
	public PostgreSqlBusinessTargetVerifier(
			PostgreSqlTargetProperties properties,
			@Autowired(required = false) @Qualifier(PostgreSqlTargetClientConfig.DATASOURCE_BEAN) DataSource dataSource,
			MigrationPromotionRepository promotionRepository,
			MigrationPromotionBatchRepository promotionBatchRepository) {
		this.properties = properties;
		this.dataSource = dataSource;
		this.promotionRepository = promotionRepository;
		this.promotionBatchRepository = promotionBatchRepository;
	}

	public TargetVerification verify(UUID migrationRunId, Map<String, Object> configuration) {
		if (dataSource == null) {
			return TargetVerification.unavailable(
					"BUSINESS_TARGET",
					"PostgreSQL target datasource is not configured");
		}
		PromotionTargetConfiguration promotion = requireEnabledPromotion(configuration);
		MigrationPromotion executed = latestExecuted(migrationRunId);
		List<Long> promotedBatchIds = promotedBatchIds(executed);
		if (promotedBatchIds.isEmpty()) {
			throw notExecuted("No successfully promoted batches are available to verify");
		}
		if (promotion.conflictKeys() == null || promotion.conflictKeys().isEmpty()) {
			Map<String, Object> attributes = baseAttributes(executed, promotion, promotedBatchIds);
			attributes.put("errorCode", MigrationErrorCodes.TARGET_IDENTITY_NOT_CONFIGURED);
			attributes.put("expectedCount", 0L);
			return new TargetVerification(
					false,
					null,
					"BUSINESS_TARGET",
					false,
					"Promotion conflictKeys are required for migration-scoped business verification",
					attributes);
		}

		PostgreSqlStagingTarget.Resolved staging = PostgreSqlStagingTarget.resolve(configuration, properties);
		List<String> conflictColumns = List.copyOf(promotion.conflictKeys());
		List<String> conflictPayloadKeys = conflictColumns.stream()
				.map(column -> PostgreSqlBusinessTargetSql.jsonKeyForColumn(promotion, column))
				.toList();
		List<Map.Entry<String, String>> valueColumns = promotion.columns().entrySet().stream()
				.filter(entry -> !promotion.conflictKeys().contains(entry.getValue()))
				.toList();
		try (Connection connection = dataSource.getConnection()) {
			long expected = scalar(
					connection,
					PostgreSqlBusinessTargetSql.expectedCount(staging, promotedBatchIds.size()),
					migrationRunId,
					promotedBatchIds,
					List.of(),
					List.of());
			long stagingDuplicates = scalar(
					connection,
					PostgreSqlBusinessTargetSql.stagingDuplicateIdentities(
							staging, promotedBatchIds.size(), conflictPayloadKeys),
					migrationRunId,
					promotedBatchIds,
					conflictPayloadKeys,
					List.of());
			long matched = scalar(
					connection,
					PostgreSqlBusinessTargetSql.matchedCount(
							staging, promotion, promotedBatchIds.size(), conflictColumns),
					migrationRunId,
					promotedBatchIds,
					List.of(),
					conflictPayloadKeys);
			long ambiguous = scalar(
					connection,
					PostgreSqlBusinessTargetSql.ambiguousBusinessMatches(
							staging, promotion, promotedBatchIds.size(), conflictColumns),
					migrationRunId,
					promotedBatchIds,
					List.of(),
					conflictPayloadKeys);
			long valueMismatches = scalar(
					connection,
					PostgreSqlBusinessTargetSql.valueMismatchCount(
							staging, promotion, promotedBatchIds.size(), conflictColumns, valueColumns),
					migrationRunId,
					promotedBatchIds,
					conflictPayloadKeys,
					valueColumns.stream().map(Map.Entry::getKey).toList());

			Map<String, Object> attributes = baseAttributes(executed, promotion, promotedBatchIds);
			attributes.put("expectedCount", expected);
			attributes.put("matchedCount", matched);
			attributes.put("stagingDuplicateIdentities", stagingDuplicates);
			attributes.put("ambiguousBusinessMatches", ambiguous);
			attributes.put("valueMismatchCount", valueMismatches);
			boolean duplicateIdentity = stagingDuplicates > 0 || ambiguous > 0;
			boolean valueMismatch = valueMismatches > 0;
			attributes.put("duplicateIdentity", duplicateIdentity);
			attributes.put("valueMismatch", valueMismatch);
			if (duplicateIdentity) {
				attributes.put("errorCode", MigrationErrorCodes.TARGET_IDENTITY_AMBIGUOUS);
			} else if (valueMismatch) {
				attributes.put("errorCode", MigrationErrorCodes.TARGET_VALUE_MISMATCH);
			}
			boolean verified = !duplicateIdentity && !valueMismatch && matched == expected;
			return new TargetVerification(
					true,
					matched,
					"BUSINESS_TARGET",
					verified,
					verified
							? "Promoted staging rows match the configured business table"
							: "Business-target verification found differences",
					attributes);
		} catch (SQLException ex) {
			throw new TargetInfrastructureException(
					MigrationErrorCodes.TARGET_DATABASE_CONNECTION_ERROR,
					"PostgreSQL business-target verification failed: " + TargetFailureDiagnostics.describe(ex),
					ex);
		}
	}

	private MigrationPromotion latestExecuted(UUID runId) {
		return promotionRepository.findByMigrationRun_IdOrderByCreatedAtDesc(runId).stream()
				.filter(promotion -> EXECUTED.contains(promotion.getStatus()))
				.findFirst()
				.orElseThrow(() -> notExecuted("Business-target verification requires an executed promotion"));
	}

	private List<Long> promotedBatchIds(MigrationPromotion promotion) {
		List<Long> ids = new ArrayList<>();
		for (MigrationPromotionBatch batch : promotionBatchRepository
				.findByPromotion_IdAndStatusOrderByMigrationBatch_BatchNumberAsc(
						promotion.getId(), PromotionBatchStatus.PROMOTED)) {
			if (batch.getMigrationBatch() != null) {
				ids.add(batch.getMigrationBatch().getId());
			}
		}
		return ids;
	}

	private static PromotionTargetConfiguration requireEnabledPromotion(Map<String, Object> configuration) {
		if (configuration == null || !configuration.containsKey("promotion")) {
			throw new PromotionException(
					HttpStatus.BAD_REQUEST.value(),
					MigrationErrorCodes.PROMOTION_NOT_CONFIGURED,
					"Promotion is not configured");
		}
		PromotionTargetConfiguration parsed = PromotionTargetConfiguration.parse(configuration);
		if (!parsed.enabled()) {
			throw new PromotionException(
					HttpStatus.BAD_REQUEST.value(),
					MigrationErrorCodes.PROMOTION_DISABLED,
					"Promotion is disabled");
		}
		return parsed;
	}

	private static PromotionException notExecuted(String message) {
		return new PromotionException(
				HttpStatus.CONFLICT.value(),
				MigrationErrorCodes.PROMOTION_NOT_EXECUTED,
				message);
	}

	private static Map<String, Object> baseAttributes(
			MigrationPromotion promotion,
			PromotionTargetConfiguration configuration,
			List<Long> promotedBatchIds) {
		Map<String, Object> attributes = new LinkedHashMap<>();
		attributes.put("promotionId", promotion.getId().toString());
		attributes.put("promotionStatus", promotion.getStatus().name());
		attributes.put("promotedBatchIds", List.copyOf(promotedBatchIds));
		attributes.put("promotionStrategy", configuration.strategy().name());
		attributes.put("businessSchema", configuration.schema());
		attributes.put("businessTable", configuration.table());
		return attributes;
	}

	private static long scalar(
			Connection connection,
			String sql,
			UUID runId,
			List<Long> batchIds,
			List<String> leadingJsonKeys,
			List<String> trailingJsonKeys) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			int expectedBinds = 1 + batchIds.size() + leadingJsonKeys.size() + trailingJsonKeys.size();
			int placeholders = countPlaceholders(sql);
			if (placeholders != expectedBinds) {
				throw new SQLException("Placeholder mismatch sql=" + sql
						+ " expectedBinds=" + expectedBinds + " placeholders=" + placeholders);
			}
			int index = 1;
			for (String jsonKey : leadingJsonKeys) {
				statement.setString(index++, jsonKey);
			}
			statement.setObject(index++, runId);
			for (Long batchId : batchIds) {
				statement.setLong(index++, batchId);
			}
			for (String jsonKey : trailingJsonKeys) {
				statement.setString(index++, jsonKey);
			}
			try (ResultSet resultSet = statement.executeQuery()) {
				resultSet.next();
				return resultSet.getLong(1);
			}
		} catch (SQLException ex) {
			throw new SQLException(ex.getMessage() + " sql=" + sql, ex);
		}
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
