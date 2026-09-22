package com.etp.migration.connector.target.uam;

import com.etp.migration.connector.target.TargetInfrastructureException;
import com.etp.migration.processing.MigrationErrorCodes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@Component
public class UamAccessTokenProvider {

	private static final Logger log = LoggerFactory.getLogger(UamAccessTokenProvider.class);

	private final UamTargetProperties properties;
	private final RestClient restClient;
	private final ObjectMapper objectMapper = new ObjectMapper();

	public UamAccessTokenProvider(UamTargetProperties properties) {
		this.properties = properties;
		this.restClient = restClient(properties);
	}

	public Optional<String> accessToken() {
		if (properties.tokenUri() == null || properties.tokenUri().isBlank()) {
			return Optional.empty();
		}
		if (properties.clientId() == null || properties.clientId().isBlank()) {
			throw new TargetInfrastructureException(
					MigrationErrorCodes.TARGET_CONFIGURATION_ERROR,
					"UAM authentication is not configured");
		}
		try {
			int status;
			String body;
			try {
				var entity = restClient.post()
						.uri(properties.tokenUri())
						.contentType(MediaType.APPLICATION_FORM_URLENCODED)
						.body(tokenForm())
						.retrieve()
						.toEntity(String.class);
				status = entity.getStatusCode().value();
				body = entity.getBody();
			} catch (org.springframework.web.client.RestClientResponseException ex) {
				status = ex.getStatusCode().value();
				body = "";
			}
			if (status < 200 || status >= 300) {
				throw new TargetInfrastructureException(
						MigrationErrorCodes.TARGET_CONFIGURATION_ERROR,
						"UAM authentication failed");
			}
			JsonNode node = objectMapper.readTree(body == null ? "{}" : body);
			if (!node.hasNonNull("access_token")) {
				throw new TargetInfrastructureException(
						MigrationErrorCodes.TARGET_CONFIGURATION_ERROR,
						"UAM authentication failed");
			}
			return Optional.of(node.get("access_token").asText());
		} catch (TargetInfrastructureException ex) {
			throw ex;
		} catch (ResourceAccessException ex) {
			log.warn("UAM token request failed due to a transport error");
			throw new TargetInfrastructureException(
					isTimeout(ex) ? MigrationErrorCodes.TARGET_TIMEOUT : MigrationErrorCodes.TARGET_CONNECTION_ERROR,
					"UAM authentication failed",
					ex);
		} catch (Exception ex) {
			throw new TargetInfrastructureException(
					MigrationErrorCodes.TARGET_CONFIGURATION_ERROR,
					"UAM authentication failed",
					ex);
		}
	}

	private String tokenForm() {
		StringBuilder form = new StringBuilder("grant_type=client_credentials");
		form.append("&client_id=").append(url(properties.clientId()));
		form.append("&client_secret=").append(url(properties.clientSecret() == null ? "" : properties.clientSecret()));
		if (properties.scope() != null && !properties.scope().isBlank()) {
			form.append("&scope=").append(url(properties.scope()));
		}
		return form.toString();
	}

	private static String url(String value) {
		return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static boolean isTimeout(Throwable error) {
		Throwable current = error;
		while (current != null) {
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
}
