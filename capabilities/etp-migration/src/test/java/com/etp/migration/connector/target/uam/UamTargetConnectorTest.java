package com.etp.migration.connector.target.uam;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.etp.migration.connector.target.LoadableTargetRecord;
import com.etp.migration.connector.target.MigrationTargetContext;
import com.etp.migration.connector.target.TargetInfrastructureException;
import com.etp.migration.connector.target.TargetLoadModeNotSupportedException;
import com.etp.migration.connector.target.TargetLoadResult;
import com.etp.migration.entity.LoadMode;
import com.etp.migration.processing.MigrationErrorCodes;
import com.etp.migration.processing.TargetRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class UamTargetConnectorTest {

	@RegisterExtension
	static WireMockExtension uam = WireMockExtension.newInstance()
			.options(wireMockConfig().dynamicPort())
			.build();

	@Test
	void successfulCreateReturnsGenericTargetId() {
		uam.stubFor(post(urlPathEqualTo("/api/v1/address"))
				.willReturn(aResponse().withStatus(201).withBody("{\"id\":\"uam-99\"}")));
		TargetLoadResult result = connector().load(context(LoadMode.SINGLE), List.of(record(1L, "123 Main")));
		assertThat(result.successCount()).isEqualTo(1);
		assertThat(result.targetReferences().get(0).targetId()).isEqualTo("uam-99");
		uam.verify(postRequestedFor(urlPathEqualTo("/api/v1/address"))
				.withHeader("Idempotency-Key", equalTo(key(1L)))
				.withRequestBody(equalToJson("{\"streetName\":\"123 Main\"}")));
	}

	@Test
	void http400IsNonRetryableRecordError() {
		uam.stubFor(post(urlPathEqualTo("/api/v1/address")).willReturn(aResponse().withStatus(400)));
		TargetLoadResult result = connector().load(context(LoadMode.SINGLE), List.of(record(1L, "x")));
		assertThat(result.failedCount()).isEqualTo(1);
		assertThat(result.errors().get(0).errorCode()).isEqualTo(MigrationErrorCodes.TARGET_4XX);
		assertThat(result.errors().get(0).retryable()).isFalse();
	}

	@Test
	void http401IsInfrastructureError() {
		uam.stubFor(post(urlPathEqualTo("/api/v1/address")).willReturn(aResponse().withStatus(401)));
		assertThatThrownBy(() -> connector().load(context(LoadMode.SINGLE), List.of(record(1L, "x"))))
				.isInstanceOf(TargetInfrastructureException.class)
				.extracting(ex -> ((TargetInfrastructureException) ex).errorCode())
				.isEqualTo(MigrationErrorCodes.TARGET_CONFIGURATION_ERROR);
	}

	@Test
	void http403And404AreConfigurationErrors() {
		uam.stubFor(post(urlPathEqualTo("/api/v1/address")).willReturn(aResponse().withStatus(403)));
		assertThatThrownBy(() -> connector().load(context(LoadMode.SINGLE), List.of(record(1L, "x"))))
				.isInstanceOf(TargetInfrastructureException.class);
		uam.resetAll();
		uam.stubFor(post(urlPathEqualTo("/api/v1/address")).willReturn(aResponse().withStatus(404)));
		assertThatThrownBy(() -> connector().load(context(LoadMode.SINGLE), List.of(record(1L, "x"))))
				.isInstanceOf(TargetInfrastructureException.class)
				.extracting(ex -> ((TargetInfrastructureException) ex).errorCode())
				.isEqualTo(MigrationErrorCodes.TARGET_CONFIGURATION_ERROR);
	}

	@Test
	void http409IsNonRetryableRecordConflict() {
		uam.stubFor(post(urlPathEqualTo("/api/v1/address")).willReturn(aResponse().withStatus(409)));
		TargetLoadResult result = connector().load(context(LoadMode.SINGLE), List.of(record(1L, "x")));
		assertThat(result.errors().get(0).retryable()).isFalse();
	}

	@Test
	void http429IsRetryableRecordError() {
		uam.stubFor(post(urlPathEqualTo("/api/v1/address")).willReturn(aResponse().withStatus(429)));
		TargetLoadResult result = connector().load(context(LoadMode.SINGLE), List.of(record(1L, "x")));
		assertThat(result.errors().get(0).retryable()).isTrue();
	}

	@Test
	void http500IsRetryableRecordError() {
		uam.stubFor(post(urlPathEqualTo("/api/v1/address")).willReturn(aResponse().withStatus(500)));
		TargetLoadResult result = connector().load(context(LoadMode.SINGLE), List.of(record(1L, "x")));
		assertThat(result.errors().get(0).errorCode()).isEqualTo(MigrationErrorCodes.TARGET_5XX);
		assertThat(result.errors().get(0).retryable()).isTrue();
	}

	@Test
	void timeoutIsRetryableRecordError() {
		uam.stubFor(post(urlPathEqualTo("/api/v1/address"))
				.willReturn(aResponse().withStatus(201).withFixedDelay(2_000).withBody("{\"id\":\"late\"}")));
		UamTargetConnector slow = connector(Duration.ofMillis(50));
		TargetLoadResult result = slow.load(context(LoadMode.SINGLE), List.of(record(1L, "x")));
		assertThat(result.errors().get(0).errorCode()).isEqualTo(MigrationErrorCodes.TARGET_TIMEOUT);
		assertThat(result.errors().get(0).retryable()).isTrue();
	}

	@Test
	void malformedSuccessBodyIsInvalidResponse() {
		uam.stubFor(post(urlPathEqualTo("/api/v1/address")).willReturn(aResponse().withStatus(200).withBody("{not-json")));
		TargetLoadResult result = connector().load(context(LoadMode.SINGLE), List.of(record(1L, "x")));
		assertThat(result.errors().get(0).errorCode()).isEqualTo(MigrationErrorCodes.TARGET_INVALID_RESPONSE);
	}

	@Test
	void bulkModeIsNotSupported() {
		assertThatThrownBy(() -> connector().load(context(LoadMode.BULK), List.of(record(1L, "x"))))
				.isInstanceOf(TargetLoadModeNotSupportedException.class);
	}

	@Test
	void authenticationFailureDoesNotExposeSecret() {
		uam.stubFor(post(urlPathEqualTo("/oauth/token")).willReturn(aResponse().withStatus(401).withBody("denied")));
		UamTargetProperties properties = properties(Duration.ofSeconds(2), uam.getRuntimeInfo().getHttpBaseUrl() + "/oauth/token", "migration-client", "super-secret");
		UamAccessTokenProvider provider = new UamAccessTokenProvider(properties);
		assertThatThrownBy(provider::accessToken)
				.isInstanceOf(TargetInfrastructureException.class)
				.hasMessage("UAM authentication failed")
				.hasMessageNotContaining("super-secret");
	}

	@Test
	void requestContainsOnlyMappedFields() {
		uam.stubFor(post(urlPathEqualTo("/api/v1/address"))
				.willReturn(aResponse().withStatus(201).withBody("{\"id\":\"1\"}")));
		connector().load(context(LoadMode.SINGLE), List.of(record(3L, "Only Street")));
		uam.verify(postRequestedFor(urlPathEqualTo("/api/v1/address"))
				.withRequestBody(equalToJson("{\"streetName\":\"Only Street\"}"))
				.withHeader("Idempotency-Key", containing(":3")));
	}

	private UamTargetConnector connector() {
		return connector(Duration.ofSeconds(2));
	}

	private UamTargetConnector connector(Duration timeout) {
		UamTargetProperties properties = properties(timeout, "", "", "");
		return new UamTargetConnector(properties, new UamAccessTokenProvider(properties), new ObjectMapper());
	}

	private UamTargetProperties properties(Duration timeout, String tokenUri, String clientId, String clientSecret) {
		return new UamTargetProperties(
				uam.getRuntimeInfo().getHttpBaseUrl(),
				"/api/v1/{entity}",
				timeout,
				timeout,
				tokenUri,
				clientId,
				clientSecret,
				"");
	}

	private static MigrationTargetContext context(LoadMode loadMode) {
		return new MigrationTargetContext(
				UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
				7L,
				1,
				"UAM",
				"address",
				loadMode,
				Map.of());
	}

	private static LoadableTargetRecord record(long number, String streetName) {
		return new LoadableTargetRecord(
				number,
				new TargetRecord(Map.of("streetName", streetName)),
				key(number));
	}

	private static String key(long number) {
		return "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa:7:" + number;
	}
}
