package com.etp.migration.config;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The identity recorded in created_by, updated_by and requested_by columns. It always comes from the authenticated
 * principal, never from request bodies.
 */
public final class AuditActor {

	public static final String SYSTEM = "system";
	public static final String ANONYMOUS = "anonymous";
	static final String CLIENT_PREFIX = "client:";
	static final int MAX_LENGTH = 100;

	private AuditActor() {
	}

	public static String current() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null) {
			return SYSTEM;
		}
		if (authentication instanceof AnonymousAuthenticationToken) {
			return ANONYMOUS;
		}
		if (authentication instanceof JwtAuthenticationToken token) {
			return fromJwt(token.getToken());
		}
		throw new AuditIdentityException("The authenticated principal type cannot be recorded as an audit actor");
	}

	static String fromJwt(Jwt jwt) {
		String subject = jwt.getSubject();
		if (subject != null && !subject.isBlank()) {
			if (subject.equals(SYSTEM) || subject.equals(ANONYMOUS) || subject.startsWith(CLIENT_PREFIX)) {
				throw new AuditIdentityException("The access token subject collides with a reserved audit actor name");
			}
			return checked(subject);
		}
		String client = firstNonBlank(jwt.getClaimAsString("azp"), jwt.getClaimAsString("client_id"));
		if (client == null) {
			throw new AuditIdentityException("The access token has no subject or client identifier to record as the audit actor");
		}
		return checked(CLIENT_PREFIX + client);
	}

	private static String checked(String actor) {
		if (actor.length() > MAX_LENGTH) {
			throw new AuditIdentityException("The access token identity is longer than " + MAX_LENGTH
					+ " characters and cannot be recorded as the audit actor");
		}
		if (actor.chars().anyMatch(Character::isISOControl)) {
			throw new AuditIdentityException("The access token identity contains control characters");
		}
		return actor;
	}

	private static String firstNonBlank(String first, String second) {
		if (first != null && !first.isBlank()) {
			return first;
		}
		return second != null && !second.isBlank() ? second : null;
	}
}
