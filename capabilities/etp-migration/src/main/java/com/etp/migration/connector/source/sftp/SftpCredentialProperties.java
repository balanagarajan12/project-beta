package com.etp.migration.connector.source.sftp;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "etp.migration.source.sftp")
public class SftpCredentialProperties {

	private Duration connectTimeout = Duration.ofSeconds(15);
	private Duration idleTimeout = Duration.ofMinutes(10);
	private boolean acceptUnknownHostKeys;
	private Path knownHostsFile;
	private Map<String, Credential> credentials = new LinkedHashMap<>();

	public Duration getConnectTimeout() {
		return connectTimeout;
	}

	public void setConnectTimeout(Duration connectTimeout) {
		this.connectTimeout = connectTimeout;
	}

	public Duration getIdleTimeout() {
		return idleTimeout;
	}

	/**
	 * Bounds the wait for each SFTP response and the time an SSH session may carry no traffic. It does not bound the
	 * total transfer time. Zero would disable both limits, so only positive values are accepted.
	 */
	public void setIdleTimeout(Duration idleTimeout) {
		if (idleTimeout == null || idleTimeout.isZero() || idleTimeout.isNegative()) {
			throw new IllegalArgumentException("etp.migration.source.sftp.idle-timeout must be a positive duration");
		}
		this.idleTimeout = idleTimeout;
	}

	public boolean isAcceptUnknownHostKeys() {
		return acceptUnknownHostKeys;
	}

	public void setAcceptUnknownHostKeys(boolean acceptUnknownHostKeys) {
		this.acceptUnknownHostKeys = acceptUnknownHostKeys;
	}

	public Path getKnownHostsFile() {
		return knownHostsFile;
	}

	public void setKnownHostsFile(Path knownHostsFile) {
		this.knownHostsFile = knownHostsFile;
	}

	public Map<String, Credential> getCredentials() {
		return credentials;
	}

	public void setCredentials(Map<String, Credential> credentials) {
		this.credentials = credentials == null ? new LinkedHashMap<>() : credentials;
	}

	public static class Credential {

		private String username;
		private String password;
		private List<String> hostKeyFingerprints = new ArrayList<>();

		public String getUsername() {
			return username;
		}

		public void setUsername(String username) {
			this.username = username;
		}

		public String getPassword() {
			return password;
		}

		public void setPassword(String password) {
			this.password = password;
		}

		public List<String> getHostKeyFingerprints() {
			return hostKeyFingerprints;
		}

		public void setHostKeyFingerprints(List<String> hostKeyFingerprints) {
			this.hostKeyFingerprints = hostKeyFingerprints == null ? new ArrayList<>() : hostKeyFingerprints;
		}
	}
}
