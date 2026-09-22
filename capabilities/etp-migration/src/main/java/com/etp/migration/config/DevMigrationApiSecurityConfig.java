package com.etp.migration.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Temporary local-development access for migration configuration APIs.
 * Active only with the {@code dev} Spring profile. OAuth2 remains configured
 * on the primary security filter chain for all other profiles.
 */
@Configuration
@Profile("dev")
public class DevMigrationApiSecurityConfig {

	@Bean
	@Order(1)
	public SecurityFilterChain devMigrationApiSecurityFilterChain(HttpSecurity http) throws Exception {
		http
				.securityMatcher(
						"/api/v1/migrations",
						"/api/v1/migrations/**",
						"/api/v1/migration-configurations",
						"/api/v1/migration-configurations/**",
						"/api/v1/mappings",
						"/api/v1/mappings/**",
						"/api/v1/migration-runs",
						"/api/v1/migration-runs/**")
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
		return http.build();
	}
}
