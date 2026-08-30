package com.etp.iam.environment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class KeycloakRealmHealthIndicator implements HealthIndicator {

	private final RestClient restClient;
	private final String realmUrl;

	public KeycloakRealmHealthIndicator(
			RestClient restClient,
			@Value("${keycloak.base-url}") String keycloakBaseUrl,
			@Value("${keycloak.realm}") String realm) {
		this.restClient = restClient;
		this.realmUrl = keycloakBaseUrl.replaceAll("/$", "") + "/realms/" + realm;
	}

	@Override
	public Health health() {
		try {
			restClient.get()
					.uri(realmUrl)
					.retrieve()
					.toBodilessEntity();
			return Health.up()
					.withDetail("keycloak", "realm endpoint reachable")
					.build();
		}
		catch (RestClientException ex) {
			return Health.down(ex)
					.withDetail("keycloak", "realm endpoint unreachable")
					.build();
		}
	}
}
