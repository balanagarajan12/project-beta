package com.etp.migration.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class AuditActorTest {

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void noAuthenticationIsTheSystemActor() {
		assertThat(AuditActor.current()).isEqualTo("system");
	}

	@Test
	void anonymousAccessIsRecordedAsAnonymous() {
		SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
				"key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

		assertThat(AuditActor.current()).isEqualTo("anonymous");
	}

	@Test
	void userTokenIsRecordedBySubjectNotByDisplayName() {
		authenticate(jwt -> jwt.subject("8f14e45f-ceea-467f-a0e6-0a1b2c3d4e5f").claim("preferred_username", "alice"));

		assertThat(AuditActor.current()).isEqualTo("8f14e45f-ceea-467f-a0e6-0a1b2c3d4e5f");
	}

	@Test
	void serviceAccountTokenWithSubjectIsRecordedBySubject() {
		authenticate(jwt -> jwt.subject("2c1b7e1a-0000-4000-8000-000000000001")
				.claim("azp", "etp-batch-runner")
				.claim("preferred_username", "service-account-etp-batch-runner"));

		assertThat(AuditActor.current()).isEqualTo("2c1b7e1a-0000-4000-8000-000000000001");
	}

	@Test
	void clientTokenWithoutSubjectIsRecordedAsClient() {
		authenticate(jwt -> jwt.claim("azp", "etp-batch-runner"));
		assertThat(AuditActor.current()).isEqualTo("client:etp-batch-runner");

		authenticate(jwt -> jwt.claim("client_id", "etp-ops"));
		assertThat(AuditActor.current()).isEqualTo("client:etp-ops");
	}

	@Test
	void tokenWithoutSubjectOrClientIsRejected() {
		authenticate(jwt -> jwt.claim("scope", "openid"));

		assertThatThrownBy(AuditActor::current)
				.isInstanceOf(AuditIdentityException.class)
				.hasMessageContaining("no subject or client identifier");
	}

	@Test
	void subjectsThatCouldImpersonateReservedActorsAreRejected() {
		for (String subject : List.of("system", "anonymous", "client:etp-batch-runner")) {
			authenticate(jwt -> jwt.subject(subject));
			assertThatThrownBy(AuditActor::current)
					.as(subject)
					.isInstanceOf(AuditIdentityException.class)
					.hasMessageContaining("reserved");
		}
	}

	@Test
	void identitiesThatDoNotFitTheAuditColumnAreRejectedRatherThanTruncated() {
		authenticate(jwt -> jwt.subject("s".repeat(100)));
		assertThat(AuditActor.current()).hasSize(100);

		authenticate(jwt -> jwt.subject("s".repeat(101)));
		assertThatThrownBy(AuditActor::current).isInstanceOf(AuditIdentityException.class);

		authenticate(jwt -> jwt.claim("azp", "c".repeat(94)));
		assertThatThrownBy(AuditActor::current).isInstanceOf(AuditIdentityException.class);
	}

	@Test
	void controlCharactersAreRejected() {
		authenticate(jwt -> jwt.subject("alice\nsystem"));

		assertThatThrownBy(AuditActor::current).isInstanceOf(AuditIdentityException.class);
	}

	@Test
	void otherPrincipalTypesAreRejected() {
		SecurityContextHolder.getContext().setAuthentication(
				UsernamePasswordAuthenticationToken.authenticated("alice", "n/a", List.of()));

		assertThatThrownBy(AuditActor::current).isInstanceOf(AuditIdentityException.class);
	}

	private static void authenticate(Consumer<Jwt.Builder> claims) {
		Jwt.Builder builder = Jwt.withTokenValue("token")
				.header("alg", "RS256")
				.issuedAt(Instant.now())
				.expiresAt(Instant.now().plusSeconds(60));
		claims.accept(builder);
		SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(builder.build()));
	}
}
