package com.etp.migration.connector.source.sftp;

import com.etp.migration.connector.source.SftpSourceException;
import com.etp.migration.processing.MigrationErrorCodes;
import org.springframework.stereotype.Component;

@Component
public class PropertySftpCredentialResolver implements SftpCredentialResolver {

	private final SftpCredentialProperties properties;

	public PropertySftpCredentialResolver(SftpCredentialProperties properties) {
		this.properties = properties;
	}

	@Override
	public SftpCredential resolve(String credentialRef) {
		if (credentialRef == null || credentialRef.isBlank()) {
			throw new SftpSourceException(
					MigrationErrorCodes.SFTP_AUTHENTICATION_FAILED,
					"SFTP credential reference could not be resolved");
		}
		SftpCredentialProperties.Credential credential = properties.getCredentials().get(credentialRef);
		if (credential == null
				|| credential.getUsername() == null
				|| credential.getUsername().isBlank()
				|| credential.getPassword() == null
				|| credential.getPassword().isBlank()) {
			throw new SftpSourceException(
					MigrationErrorCodes.SFTP_AUTHENTICATION_FAILED,
					"SFTP credential reference could not be resolved");
		}
		return new SftpCredential(credential.getUsername(), credential.getPassword());
	}
}
