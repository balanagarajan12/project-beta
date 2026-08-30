package com.etp.iam.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@SuppressWarnings("rawtypes")
class KeycloakRealmHealthIndicatorTest {

	@Test
	void healthIsUpWhenRealmEndpointResponds() {
		RestClient restClient = mock(RestClient.class);
		RestClient.RequestHeadersUriSpec requestSpec = mock(RestClient.RequestHeadersUriSpec.class);
		RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

		when(restClient.get()).thenReturn(requestSpec);
		when(requestSpec.uri(any(String.class))).thenReturn(requestSpec);
		when(requestSpec.retrieve()).thenReturn(responseSpec);
		when(responseSpec.toBodilessEntity()).thenReturn(null);

		KeycloakRealmHealthIndicator indicator =
				new KeycloakRealmHealthIndicator(restClient, "http://localhost:8180", "etp");
		Health health = indicator.health();

		assertThat(health.getStatus().getCode()).isEqualTo("UP");
	}

	@Test
	void healthIsDownWhenRealmEndpointFails() {
		RestClient restClient = mock(RestClient.class);
		RestClient.RequestHeadersUriSpec requestSpec = mock(RestClient.RequestHeadersUriSpec.class);
		RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);

		when(restClient.get()).thenReturn(requestSpec);
		when(requestSpec.uri(any(String.class))).thenReturn(requestSpec);
		when(requestSpec.retrieve()).thenReturn(responseSpec);
		when(responseSpec.toBodilessEntity()).thenThrow(new RestClientException("connection refused"));

		KeycloakRealmHealthIndicator indicator =
				new KeycloakRealmHealthIndicator(restClient, "http://localhost:8180", "etp");
		Health health = indicator.health();

		assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
	}
}
