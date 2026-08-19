package com.etp.iam.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

	@Bean
	public OpenAPI iamOpenApi() {
		return new OpenAPI()
				.info(new Info()
						.title("ETP IAM API")
						.description("""
								ETP IAM capability (`etp.iam`) — authentication and authorization services.

								**Human user authentication:** `POST /api/v1/auth/login` (public) — username + password for ETP Core UI Phase 1.
								**System-to-system authentication:** `POST /api/v1/auth/token` (public) — OAuth2 `client_credentials` for services.
								**Authorization:** `POST /api/v1/authorization/check` (Bearer JWT required).

								Passwords and client secrets must only be transmitted over HTTPS in deployed environments.
								Passwords are never persisted by IAM, never returned in responses, and must not appear in URLs or logs.
								Keycloak confidential client credentials used for human login remain server-side in IAM only.
								""")
						.version("0.0.1-SNAPSHOT"))
				.components(new Components()
						.addSecuritySchemes("bearer-jwt", new SecurityScheme()
								.type(SecurityScheme.Type.HTTP)
								.scheme("bearer")
								.bearerFormat("JWT")
								.description("Keycloak-issued JWT access token from login or client_credentials")));
	}
}
