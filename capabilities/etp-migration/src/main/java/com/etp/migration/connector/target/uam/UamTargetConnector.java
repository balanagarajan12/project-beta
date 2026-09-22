package com.etp.migration.connector.target.uam;

import com.etp.migration.connector.target.LoadableTargetRecord;
import com.etp.migration.connector.target.MigrationTargetConnector;
import com.etp.migration.connector.target.MigrationTargetContext;
import com.etp.migration.connector.target.TargetInfrastructureException;
import com.etp.migration.connector.target.TargetLoadModeNotSupportedException;
import com.etp.migration.connector.target.TargetLoadResult;
import com.etp.migration.connector.target.TargetRecordError;
import com.etp.migration.connector.target.TargetReference;
import com.etp.migration.entity.LoadMode;
import com.etp.migration.entity.MigrationRecordStatus;
import com.etp.migration.processing.MigrationErrorCodes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Placeholder UAM REST adapter. No UAM OpenAPI is present in this repository, so the contract is:
 * {@code POST {baseUrl}/api/v1/{entity}} with a JSON body equal to already-mapped {@code TargetRecord}
 * fields. Default entity is {@code address} (USPS-address-to-UAM). BATCH/BULK endpoints are not
 * invented. {@code Idempotency-Key} is sent; a real UAM that ignores the header will not be
 * retry-safe.
 */
@Component
public class UamTargetConnector implements MigrationTargetConnector {

	private static final Logger log = LoggerFactory.getLogger(UamTargetConnector.class);
	static final String TARGET_TYPE = "UAM";

	private final UamTargetProperties properties;
	private final UamAccessTokenProvider tokenProvider;
	private final ObjectMapper objectMapper;
	private final RestClient restClient;

	@Autowired
	public UamTargetConnector(UamTargetProperties properties, UamAccessTokenProvider tokenProvider) {
		this(properties, tokenProvider, new ObjectMapper());
	}

	public UamTargetConnector(
			UamTargetProperties properties,
			UamAccessTokenProvider tokenProvider,
			ObjectMapper objectMapper) {
		this.properties = properties;
		this.tokenProvider = tokenProvider;
		this.objectMapper = objectMapper;
		this.restClient = restClient(properties);
	}

	@Override
	public String targetType() {
		return TARGET_TYPE;
	}

	@Override
	public boolean supportsLoadMode(LoadMode loadMode) {
		return loadMode == LoadMode.SINGLE;
	}

	@Override
	public TargetLoadResult load(MigrationTargetContext context, List<LoadableTargetRecord> records) {
		LoadMode loadMode = context.loadMode() == null ? LoadMode.SINGLE : context.loadMode();
		if (!supportsLoadMode(loadMode)) {
			throw new TargetLoadModeNotSupportedException(TARGET_TYPE, loadMode);
		}
		if (properties.baseUrl() == null || properties.baseUrl().isBlank()) {
			throw new TargetInfrastructureException(
					MigrationErrorCodes.TARGET_CONFIGURATION_ERROR,
					"UAM base URL is not configured");
		}
		Instant started = Instant.now();
		Optional<String> token = tokenProvider.accessToken();
		List<TargetReference> references = new ArrayList<>();
		List<TargetRecordError> errors = new ArrayList<>();
		int submitted = records == null ? 0 : records.size();
		if (records != null) {
			for (LoadableTargetRecord record : records) {
				submitSingle(context, record, token, references, errors);
			}
		}
		log.info(
				"UAM target load finished runId={} batchId={} batchNumber={} targetType={} targetEntity={} loadMode={} submittedCount={} successCount={} failedCount={} durationMs={} httpCalls={}",
				context.migrationRunId(),
				context.migrationBatchId(),
				context.batchNumber(),
				context.targetType(),
				entity(context),
				loadMode,
				submitted,
				references.size(),
				errors.size(),
				Duration.between(started, Instant.now()).toMillis(),
				submitted);
		return new TargetLoadResult(submitted, references.size(), errors.size(), List.copyOf(references), List.copyOf(errors));
	}

	private void submitSingle(
			MigrationTargetContext context,
			LoadableTargetRecord record,
			Optional<String> token,
			List<TargetReference> references,
			List<TargetRecordError> errors) {
		try {
			URI uri = endpoint(context);
			String payload = objectMapper.writeValueAsString(
					record.targetRecord() == null || record.targetRecord().fields() == null
							? Map.of()
							: record.targetRecord().fields());
			RestClient.RequestBodySpec request = restClient.post()
					.uri(uri)
					.contentType(MediaType.APPLICATION_JSON)
					.header("Idempotency-Key", record.idempotencyKey());
			token.ifPresent(value -> request.header("Authorization", "Bearer " + value));
			RawHttpResponse response = request.body(payload).exchange((req, res) -> {
				String body = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
				return new RawHttpResponse(res.getStatusCode().value(), body);
			});
			UamHttpStatusClassifier.Classification classification = UamHttpStatusClassifier.classify(response.status());
			if (classification.successful()) {
				String targetId = parseTargetId(response.body());
				references.add(new TargetReference(record.sourceRecordNumber(), targetId, MigrationRecordStatus.SUCCESS));
				return;
			}
			if (classification.infrastructure()) {
				throw new TargetInfrastructureException(classification.errorCode(), "Target load failed");
			}
			errors.add(new TargetRecordError(
					record.sourceRecordNumber(),
					classification.errorCode(),
					"LOAD_FAILED",
					"Target request failed",
					classification.retryable(),
					response.status()));
		} catch (TargetInfrastructureException ex) {
			throw ex;
		} catch (RestClientException ex) {
			if (isTimeout(ex)) {
				errors.add(new TargetRecordError(
						record.sourceRecordNumber(),
						MigrationErrorCodes.TARGET_TIMEOUT,
						"LOAD_FAILED",
						"Target request timed out",
						true,
						null));
				return;
			}
			throw new TargetInfrastructureException(
					MigrationErrorCodes.TARGET_CONNECTION_ERROR,
					"Target load failed",
					ex);
		} catch (Exception ex) {
			errors.add(new TargetRecordError(
					record.sourceRecordNumber(),
					MigrationErrorCodes.TARGET_INVALID_RESPONSE,
					"LOAD_FAILED",
					"Target response could not be processed",
					false,
					null));
		}
	}

	private String parseTargetId(String body) throws Exception {
		JsonNode node = objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
		if (!node.hasNonNull("id")) {
			throw new IllegalArgumentException("missing id");
		}
		return node.get("id").asText();
	}

	private URI endpoint(MigrationTargetContext context) {
		String path = properties.path() == null || properties.path().isBlank() ? "/api/v1/{entity}" : properties.path();
		return UriComponentsBuilder.fromUriString(trimSlash(properties.baseUrl()) + (path.startsWith("/") ? path : "/" + path))
				.buildAndExpand(entity(context))
				.encode()
				.toUri();
	}

	private static String entity(MigrationTargetContext context) {
		if (context.targetEntity() == null || context.targetEntity().isBlank()) {
			return "address";
		}
		return context.targetEntity();
	}

	private static String trimSlash(String baseUrl) {
		if (baseUrl.endsWith("/")) {
			return baseUrl.substring(0, baseUrl.length() - 1);
		}
		return baseUrl;
	}

	private static boolean isTimeout(Throwable error) {
		Throwable current = error;
		while (current != null) {
			if (current instanceof java.net.http.HttpConnectTimeoutException) {
				return false;
			}
			if (current instanceof HttpTimeoutException || current instanceof java.net.SocketTimeoutException) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	private static RestClient restClient(UamTargetProperties properties) {
		Duration connect = properties.connectTimeout() == null ? Duration.ofSeconds(2) : properties.connectTimeout();
		Duration read = properties.readTimeout() == null ? Duration.ofSeconds(10) : properties.readTimeout();
		HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connect).build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
		factory.setReadTimeout(read);
		return RestClient.builder().requestFactory(factory).build();
	}

	private record RawHttpResponse(int status, String body) {
	}
}
