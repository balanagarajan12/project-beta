package com.etp.migration.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

	static final String[] OPENAPI_PATHS = {"/v3/api-docs", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"};

	@Bean
	@Order(2)
	public SecurityFilterChain securityFilterChain(
			HttpSecurity http,
			@Value("${etp.migration.openapi.enabled:false}") boolean openApiEnabled) throws Exception {
		http
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> {
					auth.requestMatchers("/actuator/health", "/actuator/health/**").permitAll();
					if (openApiEnabled) {
						auth.requestMatchers(OPENAPI_PATHS).permitAll();
					}
					auth.anyRequest().authenticated();
				})
				.oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));

		return http.build();
	}
}
