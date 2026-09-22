package com.etp.migration.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

	static final String BEARER_JWT = "bearer-jwt";

	@Bean
	public OpenAPI migrationOpenApi() {
		return new OpenAPI()
				.info(new Info()
						.title("ETP Migration API")
						.description("""
								ETP Migration capability (`etp.migration`) — configuration-driven, cloud-agnostic data migration control plane and execution engine. Supports migration definitions, source inspection, mappings and transformations, validation, batched execution, target connectors, retry/recovery, and reconciliation. Current target implementations include UAM REST SINGLE and PostgreSQL COPY BULK.
								""")
						.version("0.0.1-SNAPSHOT"))
				.components(new Components()
						.addSecuritySchemes(BEARER_JWT, new SecurityScheme()
								.type(SecurityScheme.Type.HTTP)
								.scheme("bearer")
								.bearerFormat("JWT")
								.description("Keycloak-issued JWT access token")))
				.addSecurityItem(new SecurityRequirement().addList(BEARER_JWT));
	}

	@Bean
	public OpenApiCustomizer bearerJwtOnDocumentedOperations() {
		return openApi -> {
			if (openApi.getPaths() == null) {
				return;
			}
			SecurityRequirement requirement = new SecurityRequirement().addList(BEARER_JWT);
			openApi.getPaths().values().forEach(pathItem -> pathItem.readOperations().forEach(operation -> {
				if (operation.getSecurity() == null || operation.getSecurity().isEmpty()) {
					operation.addSecurityItem(requirement);
				}
			}));
		};
	}
}
