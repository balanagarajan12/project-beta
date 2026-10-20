package com.etp.migration.connector.source.sftp;

public interface SftpCredentialResolver {

	SftpCredential resolve(String credentialRef);
}
