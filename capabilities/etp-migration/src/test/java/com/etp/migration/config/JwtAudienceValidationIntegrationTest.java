package com.etp.migration.config;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Real signed tokens against the issuer-location decoder Spring Boot builds from the production configuration.
 * WireMock serves the OpenID discovery document and JWKS in place of Keycloak.
 */
@SpringBootTest(properties = "spring.config.location=file:src/main/resources/")
@AutoConfigureMockMvc
class JwtAudienceValidationIntegrationTest {

	private static final RSAKey SIGNING_KEY = rsaKey();
	private static final WireMockServer KEYCLOAK = keycloak();
	private static final String ISSUER = KEYCLOAK.baseUrl() + "/realms/etp";

	@Autowired
	MockMvc mockMvc;

	@DynamicPropertySource
	static void issuer(DynamicPropertyRegistry registry) {
		registry.add("ETP_MIGRATION_KEYCLOAK_ISSUER_URI", () -> ISSUER);
	}

	@Nested
	class WhenAudiencesAreNotConfigured {

		@Test
		void tokenWithoutAudienceIsStillAccepted() throws Exception {
			call(token(null)).andExpect(status().isOk());
		}

		@Test
		void issuerIsStillValidated() throws Exception {
			call(token(null, "http://other-issuer/realms/etp")).andExpect(status().isUnauthorized());
		}
	}

	@Nested
	@TestPropertySource(properties = "ETP_MIGRATION_JWT_AUDIENCES=etp-migration,etp-migration-ops")
	class WhenAudiencesAreConfigured {

		@Test
		void tokenForAConfiguredAudienceIsAccepted() throws Exception {
			call(token(List.of("etp-migration"))).andExpect(status().isOk());
			call(token(List.of("account", "etp-migration-ops"))).andExpect(status().isOk());
		}

		@Test
		void tokenWithoutAudienceIsRejected() throws Exception {
			call(token(null))
					.andExpect(status().isUnauthorized())
					.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, Matchers.containsString("invalid_token")));
		}

		@Test
		void tokenForAnotherAudienceIsRejected() throws Exception {
			call(token(List.of("account"))).andExpect(status().isUnauthorized());
		}

		@Test
		void healthStaysPublic() throws Exception {
			mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
		}
	}

	private ResultActions call(String token) throws Exception {
		return mockMvc.perform(get("/api/v1/migrations").header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
	}

	private static String token(List<String> audiences) throws Exception {
		return token(audiences, ISSUER);
	}

	private static String token(List<String> audiences, String issuer) throws Exception {
		JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
				.issuer(issuer)
				.subject("user-1")
				.issueTime(new Date())
				.expirationTime(Date.from(Instant.now().plusSeconds(300)));
		if (audiences != null) {
			claims.audience(audiences);
		}
		SignedJWT jwt = new SignedJWT(
				new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(SIGNING_KEY.getKeyID()).build(),
				claims.build());
		jwt.sign(new RSASSASigner(SIGNING_KEY));
		return jwt.serialize();
	}

	private static RSAKey rsaKey() {
		try {
			return new RSAKeyGenerator(2048).keyID("test-key").generate();
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static WireMockServer keycloak() {
		WireMockServer server = new WireMockServer(wireMockConfig().dynamicPort());
		server.start();
		Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
		String issuer = server.baseUrl() + "/realms/etp";
		server.stubFor(WireMock.get(urlPathEqualTo("/realms/etp/.well-known/openid-configuration"))
				.willReturn(aResponse()
						.withHeader("Content-Type", "application/json")
						.withBody("""
								{"issuer":"%s","jwks_uri":"%s/protocol/openid-connect/certs",
								 "subject_types_supported":["public"],
								 "id_token_signing_alg_values_supported":["RS256"]}
								""".formatted(issuer, issuer))));
		server.stubFor(WireMock.get(urlPathEqualTo("/realms/etp/protocol/openid-connect/certs"))
				.willReturn(aResponse()
						.withHeader("Content-Type", "application/json")
						.withBody(new JWKSet(SIGNING_KEY.toPublicJWK()).toString())));
		return server;
	}
}
