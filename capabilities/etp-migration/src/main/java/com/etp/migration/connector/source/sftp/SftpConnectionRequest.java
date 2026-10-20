package com.etp.migration.connector.source.sftp;

/**
 * Connection values passed to the SFTP client. The password is omitted from {@link #toString()}.
 */
public record SftpConnectionRequest(
		String host,
		int port,
		String username,
		String password,
		String remotePath,
		String credentialRef
) {

	public SftpConnectionRequest(String host, int port, String username, String password, String remotePath) {
		this(host, port, username, password, remotePath, null);
	}

	@Override
	public String toString() {
		return "SftpConnectionRequest[host=" + host + ", port=" + port + ", remotePath=" + remotePath
				+ ", credentialRef=" + credentialRef + "]";
	}
}
