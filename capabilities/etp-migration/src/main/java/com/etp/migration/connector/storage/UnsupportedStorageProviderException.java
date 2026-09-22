package com.etp.migration.connector.storage;

import com.etp.migration.entity.StorageProvider;

public class UnsupportedStorageProviderException extends RuntimeException {

	public UnsupportedStorageProviderException(StorageProvider provider) {
		super("Storage provider is not supported: " + provider);
	}
}
