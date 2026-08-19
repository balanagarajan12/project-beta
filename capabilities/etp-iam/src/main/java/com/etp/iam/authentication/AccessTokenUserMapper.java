package com.etp.iam.authentication;

import com.etp.iam.authentication.dto.UserResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Maps Keycloak access token JWT claims to a user profile for login responses.
 * The token is obtained directly from Keycloak; payload is decoded for profile fields only.
 */
final class AccessTokenUserMapper {

	private AccessTokenUserMapper() {
	}

	static UserResponse mapUser(String accessToken, String fallbackUsername) {
		String payloadJson = readPayloadJson(accessToken);

		String subject = KeycloakTokenClient.readJsonStringField(payloadJson, "sub");
		String id = subject != null && !subject.isBlank() ? subject : fallbackUsername;

		String preferredUsername = KeycloakTokenClient.readJsonStringField(payloadJson, "preferred_username");
		String username = preferredUsername != null && !preferredUsername.isBlank()
				? preferredUsername
				: fallbackUsername;

		String email = KeycloakTokenClient.readJsonStringField(payloadJson, "email");
		if (email == null) {
			email = username.contains("@") ? username : "";
		}

		String displayName = KeycloakTokenClient.readJsonStringField(payloadJson, "name");
		if (displayName == null || displayName.isBlank()) {
			displayName = username;
		}

		List<String> roles = extractRealmRoles(payloadJson);

		return new UserResponse(id, username, displayName, email, roles);
	}

	private static String readPayloadJson(String accessToken) {
		String[] parts = accessToken.split("\\.");
		if (parts.length < 2) {
			throw new IllegalArgumentException("Invalid access token format");
		}

		byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
		return new String(decoded, StandardCharsets.UTF_8);
	}

	private static List<String> extractRealmRoles(String payloadJson) {
		int realmAccessIndex = payloadJson.indexOf("\"realm_access\"");
		if (realmAccessIndex < 0) {
			return List.of();
		}

		int rolesIndex = payloadJson.indexOf("\"roles\"", realmAccessIndex);
		if (rolesIndex < 0) {
			return List.of();
		}

		int arrayStart = payloadJson.indexOf('[', rolesIndex);
		int arrayEnd = payloadJson.indexOf(']', arrayStart);
		if (arrayStart < 0 || arrayEnd < arrayStart) {
			return List.of();
		}

		String rolesSection = payloadJson.substring(arrayStart + 1, arrayEnd);
		List<String> roles = new ArrayList<>();
		int searchFrom = 0;
		while (true) {
			int quoteStart = rolesSection.indexOf('"', searchFrom);
			if (quoteStart < 0) {
				break;
			}
			int quoteEnd = rolesSection.indexOf('"', quoteStart + 1);
			if (quoteEnd < 0) {
				break;
			}
			String role = rolesSection.substring(quoteStart + 1, quoteEnd);
			if (!role.isBlank()) {
				roles.add(role);
			}
			searchFrom = quoteEnd + 1;
		}

		return roles;
	}
}
