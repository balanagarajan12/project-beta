package com.etp.migration.connector.source.sftp;

import com.etp.migration.connector.source.SftpSourceException;
import com.etp.migration.processing.MigrationErrorCodes;
import java.io.IOException;
import java.io.InputStream;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.core.CoreModuleProperties;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SshdSftpRemoteClient implements SftpRemoteClient {

	private static final Logger log = LoggerFactory.getLogger(SshdSftpRemoteClient.class);

	private final SftpCredentialProperties properties;

	public SshdSftpRemoteClient(SftpCredentialProperties properties) {
		this.properties = properties;
		if (properties.isAcceptUnknownHostKeys()) {
			log.warn("etp.migration.source.sftp.accept-unknown-host-keys=true: SFTP hosts without a trusted key "
					+ "will be accepted. Use this for development only");
		}
	}

	@Override
	public SftpRemoteFile open(SftpConnectionRequest request) {
		SftpHostKeyVerifier hostKeys = SftpHostKeyVerifier.create(properties, request);
		SshClient client = SshClient.setUpDefaultClient();
		client.setServerKeyVerifier(hostKeys);
		CoreModuleProperties.IDLE_TIMEOUT.set(client, properties.getIdleTimeout());
		client.start();
		ClientSession session = null;
		SftpClient sftp = null;
		try {
			long timeout = properties.getConnectTimeout() == null
					? 15_000L
					: properties.getConnectTimeout().toMillis();
			session = client.connect(request.username(), request.host(), request.port())
					.verify(timeout)
					.getSession();
			session.addPasswordIdentity(request.password());
			var authentication = session.auth();
			authentication.verify(timeout);
			if (!authentication.isSuccess()) {
				throw new SftpSourceException(
						MigrationErrorCodes.SFTP_AUTHENTICATION_FAILED,
						"SFTP authentication failed");
			}
			sftp = SftpClientFactory.instance().createSftpClient(session);
			long size = sftp.stat(request.remotePath()).getSize();
			InputStream raw = sftp.read(request.remotePath());
			SshClient openClient = client;
			ClientSession openSession = session;
			SftpClient openSftp = sftp;
			client = null;
			session = null;
			sftp = null;
			return new SftpRemoteFile(raw, size, () -> closeQuietly(openSftp, openSession, openClient));
		} catch (RuntimeException | IOException ex) {
			closeQuietly(sftp, session, client);
			if (hostKeys.rejection() != null) {
				throw hostKeys.rejection();
			}
			RuntimeException translated = SftpSourceFailures.translate(ex);
			if (translated instanceof RuntimeException runtime) {
				throw runtime;
			}
			throw new SftpSourceException(MigrationErrorCodes.SFTP_READ_FAILED, "SFTP read failed", ex);
		}
	}

	private static void closeQuietly(SftpClient sftp, ClientSession session, SshClient client) {
		if (sftp != null) {
			try {
				sftp.close();
			} catch (IOException ignored) {
				// session close follows
			}
		}
		if (session != null) {
			try {
				session.close();
			} catch (IOException ignored) {
				// client stop follows
			}
		}
		if (client != null) {
			try {
				client.stop();
			} catch (RuntimeException ignored) {
				// already closing
			}
		}
	}
}
