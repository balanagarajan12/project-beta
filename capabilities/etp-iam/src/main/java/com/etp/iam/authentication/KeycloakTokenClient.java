package com.etp.iam.authentication;

import com.etp.iam.authentication.dto.TokenResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class KeycloakTokenClient {

	private final RestClient restClient;
	private final String tokenUrl;

	public KeycloakTokenClient(
			RestClient restClient,
			@Value("${keycloak.token-url}") String tokenUrl) {
		this.restClient = restClient;
		this.tokenUrl = tokenUrl;
	}

	public TokenResponse requestToken(String clientId, String clientSecret, String grantType) {
		MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
		formData.add("grant_type", grantType);
		formData.add("client_id", clientId);
		formData.add("client_secret", clientSecret);

		return postTokenForm(formData);
	}

	public TokenResponse requestPasswordToken(
			String clientId,
			String clientSecret,
			String username,
			String password) {
		MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
		formData.add("grant_type", "password");
		formData.add("client_id", clientId);
		formData.add("client_secret", clientSecret);
		formData.add("username", username);
		formData.add("password", password);

		return postTokenForm(formData);
	}

	private TokenResponse postTokenForm(MultiValueMap<String, String> formData) {
		try {
			return restClient.post()
					.uri(tokenUrl)
					.contentType(MediaType.APPLICATION_FORM_URLENCODED)
					.body(formData)
					.retrieve()
					.body(TokenResponse.class);
		}
		catch (RestClientResponseException ex) {
			throw mapKeycloakError(ex);
		}
	}

	private RuntimeException mapKeycloakError(RestClientResponseException ex) {
		String error = extractOAuthError(ex);
		String description = extractOAuthErrorDescription(ex);

		if (ex.getStatusCode() == HttpStatus.UNAUTHORIZED || ex.getStatusCode() == HttpStatus.BAD_REQUEST) {
			String message = "invalid_grant".equals(error)
					? "Invalid email or username, or password."
					: (description != null ? description : "Authentication failed");
			return new KeycloakAuthenticationException(message, error);
		}

		return new KeycloakTokenException(
				"Token service is temporarily unavailable",
				ex.getStatusCode().value());
	}

	private String extractOAuthError(RestClientResponseException ex) {
		return readErrorField(ex, "error");
	}

	private String extractOAuthErrorDescription(RestClientResponseException ex) {
		return readErrorField(ex, "error_description");
	}

	private String readErrorField(RestClientResponseException ex, String fieldName) {
		return readJsonStringField(ex.getResponseBodyAsString(), fieldName);
	}

	static String readJsonStringField(String responseBody, String fieldName) {
		if (responseBody == null || responseBody.isBlank()) {
			return null;
		}

		String marker = "\"" + fieldName + "\"";
		int markerIndex = responseBody.indexOf(marker);
		if (markerIndex < 0) {
			return null;
		}

		int valueStart = responseBody.indexOf('"', markerIndex + marker.length());
		if (valueStart < 0) {
			return null;
		}

		int valueEnd = responseBody.indexOf('"', valueStart + 1);
		if (valueEnd < 0) {
			return null;
		}

		return responseBody.substring(valueStart + 1, valueEnd);
	}
}
