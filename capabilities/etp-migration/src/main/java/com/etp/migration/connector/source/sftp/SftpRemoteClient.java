package com.etp.migration.connector.source.sftp;

public interface SftpRemoteClient {

	SftpRemoteFile open(SftpConnectionRequest request);
}
