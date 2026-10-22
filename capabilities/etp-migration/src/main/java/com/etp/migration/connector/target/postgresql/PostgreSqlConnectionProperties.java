package com.etp.migration.connector.target.postgresql;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Server-side PostgreSQL connection profiles. A migration selects one by name.
 * Passwords stay in this configuration and are not accepted on the migration payload.
 */
@ConfigurationProperties(prefix = "etp.migration.postgresql")
public class PostgreSqlConnectionProperties {

	private Map<String, Profile> connections = new LinkedHashMap<>();

	public Map<String, Profile> getConnections() {
		return connections;
	}

	public void setConnections(Map<String, Profile> connections) {
		this.connections = connections == null ? new LinkedHashMap<>() : connections;
	}

	public static class Profile {

		private String url;
		private String username;
		private String password;
		private Integer maxPoolSize;

		public String getUrl() {
			return url;
		}

		public void setUrl(String url) {
			this.url = url;
		}

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

		public Integer getMaxPoolSize() {
			return maxPoolSize;
		}

		public void setMaxPoolSize(Integer maxPoolSize) {
			this.maxPoolSize = maxPoolSize;
		}
	}
}
