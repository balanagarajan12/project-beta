package com.etp.migration.connector.source.sftp;

/**
 * Resolved SFTP login. The password is omitted from {@link #toString()}.
 */
public record SftpCredential(String username, String password) {

	@Override
	public String toString() {
		return "SftpCredential[username=" + username + "]";
	}
}
