package com.etp.iam.authentication.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Authenticated user profile")
public record UserResponse(
		@Schema(
				description = "Stable user identifier (Keycloak JWT `sub` when present)",
				example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
		String id,

		@Schema(description = "Username (`preferred_username` from JWT when present)", example = "platform-admin")
		String username,

		@Schema(description = "Display name (`name` from JWT when present)", example = "Platform Admin")
		String displayName,

		@Schema(description = "Email from JWT when present", example = "admin@etp.local")
		String email,

		@Schema(
				description = "Realm roles from JWT `realm_access.roles` when present",
				example = "[\"platform-admin\"]")
		List<String> roles
) {
}
